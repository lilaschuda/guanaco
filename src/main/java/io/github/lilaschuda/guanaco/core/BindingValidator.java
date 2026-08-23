package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.GuanacoDelayerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThrottlerConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class BindingValidator {

    private static final Logger log = LoggerFactory.getLogger(BindingValidator.class);

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

        Map<String, List<BindingTarget>> bindings = routeConfig.getBindings();

        if (bindings == null || bindings.isEmpty()) {
            String msg = String.format(
                    "[%s] No bindings declared in routes config. "
                    + "Expected bindings for: %s", processorName, declaredOutcomes);
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
                    "[%s] Missing bindings for route outcomes declared in code: %s. "
                    + "Add these to '%s.bindings'.",
                    processorName, missingBindings, processorName);
            handleMissing(msg);
        }

        if (!unresolvedBindings.isEmpty()) {
            String msg = String.format(
                    "[%s] Config declares bindings for unrecognized outcomes: %s. "
                    + "These don't match any RouteOutcome implementation found during startup scanning. "
                    + "Check for typos, or confirm the class exists within the scanned base package.",
                    processorName, unresolvedBindings);
            handleExtra(msg);
        }

        if (!crossCuttingBindings.isEmpty()) {
            log.info("[{}] {} binding(s) resolved as cross-cutting Split/Multicast destination(s) "
                    + "outside this processor's own sealed hierarchy: {}",
                    processorName, crossCuttingBindings.size(), crossCuttingBindings);
        }

        if (missingBindings.isEmpty() && unresolvedBindings.isEmpty()) {
            log.info("[{}] Bindings validated OK — {} routes configured", processorName, bindings.size());
        }
    }

    /**
     * Validates that no per-binding circuitBreaker override is declared on an
     * outcome that isn't a permitted subtype of the processor's sealed
     * hierarchy. Such an outcome is, by construction, only ever reachable via
     * Multicast/Split's producerTemplate.send() path, which has no Camel DSL
     * node for a circuit breaker to wrap.
     *
     * <p>
     * This does NOT catch the residual, undecidable case: a sealed- hierarchy
     * outcome that is ALSO emitted via Multicast/Split by developer code. In
     * that case the circuit breaker silently won't apply on the Multicast/Split
     * path — documented as a known limitation rather than silently ignored.
     */
    public void validateCircuitBreakerScope(String processorName, RouteConfig routeConfig,
            Class<? extends RouteOutcome<?>> routeInterface) {
        Class<?>[] permitted = routeInterface.getPermittedSubclasses();
        if (permitted == null) {
            return; // whole route is non-sealed (Multicast/Split-only) — nothing to check per-outcome
        }

        Set<String> permittedNames = Arrays.stream(permitted)
                .map(Class::getSimpleName).collect(Collectors.toSet());

        for (Map.Entry<String, List<BindingTarget>> entry : routeConfig.getBindings().entrySet()) {
            if (permittedNames.contains(entry.getKey())) {
                continue; // reachable via choice() — fine
            }
            for (BindingTarget target : entry.getValue()) {
                if (target.getCircuitBreaker() != null) {
                    throw new InvalidRouteConfigurationException(
                            "[" + processorName + "] binding '" + entry.getKey() + "' declares a circuitBreaker "
                            + "override, but this outcome is not a permitted subtype of " + routeInterface.getName()
                            + " — it can only be reached via Multicast/Split, which cannot be wrapped by a Camel "
                            + "circuit breaker DSL node. Remove this override.");
                }
            }
        }
    }

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
                    "[" + processorName + "] aggregate block must declare at least one completion "
                    + "condition greater than zero: completionSize or completionTimeoutMs.");
        }

        log.info("[{}] Aggregate config validated OK — correlationHeader='{}', strategyRef='{}'",
                processorName, agg.getCorrelationHeader(), agg.getStrategyRef());
    }

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

    public void validateResequenceConfig(String processorName, RouteConfig routeConfig) {
        GuanacoResequenceConfig reseq = routeConfig.getResequence();
        if (reseq == null) {
            return;
        }

        if (reseq.getSequenceHeader() == null || reseq.getSequenceHeader().isBlank()) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] resequence.sequenceHeader must be provided and non-blank.");
        }

        if (reseq.getMode() == null) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] resequence.mode must be provided — STREAM or BATCH.");
        }

        if (reseq.getMode() == GuanacoResequenceConfig.Mode.BATCH) {
            boolean hasCapacity = reseq.getCapacity() != null && reseq.getCapacity() > 0;
            boolean hasTimeout = reseq.getTimeoutMs() != null && reseq.getTimeoutMs() > 0;

            if (!hasCapacity && !hasTimeout) {
                throw new InvalidRouteConfigurationException(
                        "[" + processorName + "] resequence in BATCH mode must declare at least one "
                        + "completion condition greater than zero: capacity or timeoutMs.");
            }

            if (reseq.getRejectOld() != null) {
                throw new InvalidRouteConfigurationException(
                        "[" + processorName + "] resequence.rejectOld is only valid in STREAM mode — "
                        + "found alongside mode: BATCH. Remove it, or check for a typo in 'mode'.");
            }
        } else {
            if (reseq.getCapacity() != null && reseq.getCapacity() <= 0) {
                throw new InvalidRouteConfigurationException(
                        "[" + processorName + "] resequence.capacity must be greater than zero if specified.");
            }
            if (reseq.getTimeoutMs() != null && reseq.getTimeoutMs() <= 0) {
                throw new InvalidRouteConfigurationException(
                        "[" + processorName + "] resequence.timeoutMs must be greater than zero if specified.");
            }
        }

        log.info("[{}] Resequence config validated OK — sequenceHeader='{}', mode={}",
                processorName, reseq.getSequenceHeader(), reseq.getMode());
    }

    private void validateNoForbiddenSchemes(String processorName, RouteConfig routeConfig) {
        checkUri(processorName, "from", routeConfig.getFrom());

        if (routeConfig.getBindings() != null) {
            for (Map.Entry<String, List<BindingTarget>> entry : routeConfig.getBindings().entrySet()) {
                List<BindingTarget> targets = entry.getValue();
                if (targets == null) {
                    continue;
                }
                for (BindingTarget target : targets) {
                    checkUri(processorName, "bindings." + entry.getKey(), target.getUri());
                }
            }
        }
    }

    /**
     * Validates that no per-binding DSL-only policy (circuitBreaker, throttler)
     * is declared on an outcome that isn't a permitted subtype of the
     * processor's sealed hierarchy. Such an outcome is, by construction, only
     * ever reachable via Multicast/Split's producerTemplate.send() path, which
     * has no Camel DSL node for either policy to wrap.
     *
     * <p>
     * This does NOT catch the residual, undecidable case: a sealed- hierarchy
     * outcome that is ALSO emitted via Multicast/Split by developer code. In
     * that case the policy silently won't apply on the Multicast/ Split path —
     * documented as a known limitation rather than silently ignored.
     */
    public void validateDslOnlyPolicyScope(String processorName, RouteConfig routeConfig,
            Class<? extends RouteOutcome<?>> routeInterface) {
        Class<?>[] permitted = routeInterface.getPermittedSubclasses();
        if (permitted == null) {
            return; // whole route is non-sealed (Multicast/Split-only) — nothing to check per-outcome
        }

        Set<String> permittedNames = Arrays.stream(permitted)
                .map(Class::getSimpleName).collect(Collectors.toSet());

        for (Map.Entry<String, List<BindingTarget>> entry : routeConfig.getBindings().entrySet()) {
            if (permittedNames.contains(entry.getKey())) {
                continue; // reachable via choice() — fine
            }
            for (BindingTarget target : entry.getValue()) {
                if (target.getCircuitBreaker() != null) {
                    throw scopeViolation(processorName, entry.getKey(), "circuitBreaker", routeInterface);
                }
                if (target.getThrottler() != null) {
                    throw scopeViolation(processorName, entry.getKey(), "throttler", routeInterface);
                }
                if (target.getDelayer() != null) {
                    throw scopeViolation(processorName, entry.getKey(), "delayer", routeInterface);
                }
            }
        }
    }

    private InvalidRouteConfigurationException scopeViolation(String processorName, String outcomeName,
            String policyName, Class<?> routeInterface) {
        return new InvalidRouteConfigurationException(
                "[" + processorName + "] binding '" + outcomeName + "' declares a " + policyName
                + " override, but this outcome is not a permitted subtype of " + routeInterface.getName()
                + " — it can only be reached via Multicast/Split, which cannot be wrapped by a Camel "
                + policyName + " DSL node. Remove this override.");
    }

    /**
     * Validates the structural shape of an optional route-level throttler:
     * block. A no-op if none is declared. Always terminal on failure,
     * regardless of ValidationMode.
     */
    public void validateThrottlerConfig(String processorName, RouteConfig routeConfig) {
        validateThrottlerShape(processorName, "throttler", routeConfig.getThrottler());

        for (Map.Entry<String, List<BindingTarget>> entry : routeConfig.getBindings().entrySet()) {
            for (BindingTarget target : entry.getValue()) {
                if (target.getThrottler() != null) {
                    validateThrottlerShape(processorName, "bindings." + entry.getKey() + ".throttler", target.getThrottler());
                }
            }
        }
    }

    private void validateThrottlerShape(String processorName, String fieldDescription, GuanacoThrottlerConfig throttler) {
        if (throttler == null) {
            return;
        }

        if (!throttler.resolveEnabled()) {
        // Explicitly disabled — requestsPerPeriod/timePeriodMillis are
        // meaningless in this state, so there's nothing further to validate.
        log.debug("[{}] {} is explicitly disabled — skipping completeness check.",
                    processorName, fieldDescription);
            return;
        }

        if (throttler.getRequestsPerPeriod() == null || throttler.getRequestsPerPeriod() <= 0) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + ".requestsPerPeriod must be provided and greater than zero.");
        }

        if (throttler.getTimePeriodMillis() == null || throttler.getTimePeriodMillis() <= 0) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + ".timePeriodMillis must be provided and greater than zero.");
        }

        if (throttler.resolveAsyncDelayed() && throttler.resolveRejectExecution()) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + " cannot set both asyncDelayed and "
                    + "rejectExecution to true — 'never wait' and 'wait without blocking' are contradictory. "
                    + "Choose one, or leave both unset for the default blocking queue-and-wait behavior.");
        }

        log.info("[{}] {} validated OK — requestsPerPeriod={}, timePeriodMillis={}",
                processorName, fieldDescription, throttler.getRequestsPerPeriod(), throttler.getTimePeriodMillis());
    }

    public void validateDelayerConfig(String processorName, RouteConfig routeConfig) {
        validateDelayerShape(processorName, "delayer", routeConfig.getDelayer());

        for (Map.Entry<String, List<BindingTarget>> entry : routeConfig.getBindings().entrySet()) {
            for (BindingTarget target : entry.getValue()) {
                if (target.getDelayer() != null) {
                    validateDelayerShape(processorName, "bindings." + entry.getKey() + ".delayer", target.getDelayer());
                }
            }
        }
    }

    private void validateDelayerShape(String processorName, String fieldDescription, GuanacoDelayerConfig delayer) {
        if (delayer == null) {
            return;
        }

        if (!delayer.resolveEnabled()) {
            log.debug("[{}] {} is explicitly disabled — skipping completeness check.", processorName, fieldDescription);
            return;
        }

        boolean hasFixed = delayer.getDelayMs() != null;
        boolean hasStrategy = delayer.getDelayStrategyRef() != null && !delayer.getDelayStrategyRef().isBlank();

        if (hasFixed && hasStrategy) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + " sets both delayMs and delayStrategyRef — "
                    + "these are alternative sources for the same value. Set exactly one.");
        }

        if (!hasFixed && !hasStrategy) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + " must set exactly one of delayMs or delayStrategyRef.");
        }

        if (hasFixed && delayer.getDelayMs() <= 0) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + ".delayMs must be greater than zero.");
        }
    }

    private void checkUri(String processorName, String fieldDescription, String uri) {
        if (uri == null) {
            return;
        }

        String scheme = extractScheme(uri);
        if (scheme != null && FORBIDDEN_SCHEMES.contains(scheme)) {
            String msg = String.format(
                    "[%s] Forbidden scripting component scheme '%s' found in %s ('%s'). "
                    + "Guanaco does not permit dynamic script interpretation at endpoints, to preserve "
                    + "deterministic, compile-time-checked routing.",
                    processorName, scheme, fieldDescription, uri);
            log.error(msg);
            throw new ForbiddenComponentException(msg);
        }
    }

    private String extractScheme(String uri) {
        int idx = uri.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        return uri.substring(0, idx);
    }

    private void handleMissing(String message) {
        switch (mode) {
            case STRICT ->
                throw new BindingValidationException(message);
            case PERMISSIVE ->
                log.warn("PERMISSIVE MODE - ignoring missing binding. {}", message);
            case SILENT -> {
            }
        }
    }

    private void handleExtra(String message) {
        switch (mode) {
            case STRICT ->
                throw new BindingValidationException(message);
            case PERMISSIVE ->
                log.warn("PERMISSIVE MODE — ignoring unresolved binding. {}", message);
            case SILENT -> {
            }
        }
    }
}
