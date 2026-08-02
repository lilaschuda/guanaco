package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates RouteConfig integrity before routes are built, using two
 * distinct kinds of check:
 *
 * <ul>
 *   <li><b>Binding validation</b> — see {@link #validate}. Mode-sensitive
 *       (STRICT/PERMISSIVE/SILENT), as documented there.</li>
 *   <li><b>Structural / security guardrails</b> — the scripting-scheme
 *       deny-list (checked inside {@link #validate}) and
 *       {@link #validateAggregateConfig}. Both are always terminal,
 *       regardless of ValidationMode: there is no sensible "permissive"
 *       degradation for a forbidden component scheme or a structurally
 *       incomplete aggregate block.</li>
 * </ul>
 */
public class BindingValidator {

    private static final Logger log = LoggerFactory.getLogger(BindingValidator.class);

    /**
     * Component schemes that permit dynamic script interpretation at the
     * endpoint. 'language' is the real, exploitable Camel component scheme
     * (language:groovy:..., language:js:...); the rest are listed as
     * defense-in-depth in case a component with that exact scheme name is
     * ever added to the classpath. Matched against the URI's actual scheme
     * (the substring before the first ':'), never via substring containment
     * — a topic literally named "kafka:python:events" must not trip this.
     */
    private static final Set<String> FORBIDDEN_SCHEMES = Set.of(
            "language", "groovy", "js", "javascript", "mvel", "ognl", "python"
    );

    private final ValidationMode mode;

    public BindingValidator(ValidationMode mode) {
        this.mode = mode;
        log.info("Binding validator initialized in {} mode", mode);
    }

    public void validate(String processorName, Set<String> declaredOutcomes, RouteConfig routeConfig,
                          RouteOutcomeRegistry registry) {
        validateNoForbiddenSchemes(processorName, routeConfig);

        Map<String, List<String>> bindings = routeConfig.getBindings();

        if (bindings == null || bindings.isEmpty()) {
            String msg = String.format(
                "[%s] No bindings declared in routes.yaml. " +
                "Expected bindings for: %s", processorName, declaredOutcomes);
            handleMissing(msg);
            return;
        }

        Set<String> configuredKeys = bindings.keySet();

        Set<String> missingBindings = declaredOutcomes.stream()
            .filter(o -> !configuredKeys.contains(o))
            .collect(Collectors.toSet());

        Set<String> unresolvedBindings = configuredKeys.stream()
            .filter(k -> !declaredOutcomes.contains(k))
            .filter(k -> !registry.contains(k))
            .collect(Collectors.toSet());

        Set<String> crossCuttingBindings = configuredKeys.stream()
            .filter(k -> !declaredOutcomes.contains(k))
            .filter(registry::contains)
            .collect(Collectors.toSet());

        if (!missingBindings.isEmpty()) {
            String msg = String.format(
                "[%s] Missing YAML bindings for route outcomes declared in code: %s. " +
                "Add these to routes.yaml under '%s.bindings'.",
                processorName, missingBindings, processorName);
            handleMissing(msg);
        }

        if (!unresolvedBindings.isEmpty()) {
            String msg = String.format(
                "[%s] YAML declares bindings for unrecognized outcomes: %s. " +
                "These don't match any RouteOutcome implementation found during startup scanning. " +
                "Check for typos, or confirm the class exists within the scanned base package.",
                processorName, unresolvedBindings);
            handleExtra(msg);
        }

        if (!crossCuttingBindings.isEmpty()) {
            log.info("[{}] {} binding(s) resolved as cross-cutting Split/Multicast destination(s) " +
                    "outside this processor's own sealed hierarchy: {}",
                    processorName, crossCuttingBindings.size(), crossCuttingBindings);
        }

        if (missingBindings.isEmpty() && unresolvedBindings.isEmpty()) {
            log.info("[{}] Bindings validated OK — {} routes configured", processorName, bindings.size());
        }
    }

    /**
     * Validates the structural shape of an optional {@code aggregate:}
     * block. A no-op if none is declared. Always terminal on failure,
     * regardless of ValidationMode — see class-level javadoc.
     *
     * <p>This only validates shape (required fields present, at least one
     * completion condition set). It does NOT check whether strategyRef
     * actually resolves to a registered AggregationStrategy — that
     * resolution, and its own terminal failure on a missing strategy, happens
     * later, during route compilation in GuanacoRouteBuilder, since it
     * depends on what's been registered via
     * {@code GuanacoContext.registerAggregationStrategy(...)} rather than on
     * the config file alone.
     */
    public void validateAggregateConfig(String processorName, RouteConfig routeConfig) {
        GuanacoAggregateConfig agg = routeConfig.getAggregate();
        if (agg == null) {
            return;
        }

        if (agg.getCorrelationHeader() == null || agg.getCorrelationHeader().isBlank()) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] aggregate.correlationHeader must be provided and non-blank.");
        }

        if (agg.getStrategyRef() == null || agg.getStrategyRef().isBlank()) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] aggregate.strategyRef must be provided and non-blank.");
        }

        boolean hasSize = agg.getCompletionSize() != null && agg.getCompletionSize() > 0;
        boolean hasTimeout = agg.getCompletionTimeoutMs() != null && agg.getCompletionTimeoutMs() > 0;

        if (!hasSize && !hasTimeout) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] aggregate block must declare at least one completion " +
                    "condition greater than zero: completionSize or completionTimeoutMs.");
        }

        log.info("[{}] Aggregate config validated OK — correlationHeader='{}', strategyRef='{}'",
                processorName, agg.getCorrelationHeader(), agg.getStrategyRef());
    }

    /**
     * Rejects any 'from' or binding endpoint URI whose scheme (the substring
     * before the first ':') is a known scripting component scheme. Matched
     * on scheme only, never via substring containment — deliberately so that
     * a legitimate URI like "kafka:python:events-topic" (scheme = "kafka")
     * is never mistakenly flagged.
     */
    private void validateNoForbiddenSchemes(String processorName, RouteConfig routeConfig) {
        checkUri(processorName, "from", routeConfig.getFrom());

        if (routeConfig.getBindings() != null) {
            for (Map.Entry<String, List<String>> entry : routeConfig.getBindings().entrySet()) {
                List<String> uris = entry.getValue();
                if (uris == null) continue;
                for (String uri : uris) {
                    checkUri(processorName, "bindings." + entry.getKey(), uri);
                }
            }
        }
    }

    private void checkUri(String processorName, String fieldDescription, String uri) {
        if (uri == null) return;

        String scheme = extractScheme(uri);
        if (scheme != null && FORBIDDEN_SCHEMES.contains(scheme)) {
            String msg = String.format(
                "[%s] Forbidden scripting component scheme '%s' found in %s ('%s'). " +
                "Guanaco does not permit dynamic script interpretation at endpoints, to preserve " +
                "deterministic, compile-time-checked routing.",
                processorName, scheme, fieldDescription, uri);
            log.error(msg);
            throw new ForbiddenComponentException(msg);
        }
    }

    private String extractScheme(String uri) {
        int idx = uri.indexOf(':');
        if (idx <= 0) return null;
        return uri.substring(0, idx);
    }

    private void handleMissing(String message) {
        switch (mode) {
            case STRICT -> throw new BindingValidationException(message);
            case PERMISSIVE -> log.warn("PERMISSIVE MODE - ignoring missing binding. {}", message);
            case SILENT -> {}
        }
    }

    private void handleExtra(String message) {
        switch (mode) {
            case STRICT -> throw new BindingValidationException(message);
            case PERMISSIVE -> log.warn("PERMISSIVE MODE — ignoring unresolved binding. {}", message);
            case SILENT -> {}
        }
    }

    /**
     * Validates the structural shape of an optional {@code idempotent:} block.
     * A no-op if none is declared. Always terminal on failure, regardless of
     * ValidationMode — same reasoning as {@link #validateAggregateConfig}.
     */
    public void validateIdempotentConfig(String processorName, RouteConfig routeConfig) {
        GuanacoIdempotentConfig idempotent = routeConfig.getIdempotent();
        if (idempotent == null) {
            return;
        }

        if (idempotent.getMessageIdHeader() == null || idempotent.getMessageIdHeader().isBlank()) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] idempotent.messageIdHeader must be provided and non-blank.");
        }

        if (idempotent.getCapacity() != null && idempotent.getCapacity() <= 0) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] idempotent.capacity must be greater than zero if specified.");
        }

        log.info("[{}] Idempotent config validated OK — messageIdHeader='{}', capacity={}",
                processorName, idempotent.getMessageIdHeader(), idempotent.resolveCapacity());
    }
}
