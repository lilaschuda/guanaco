package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that YAML bindings for a processor are resolvable, using two
 * distinct sources of truth:
 *
 * <ul>
 *   <li><b>declaredOutcomes</b> — the processor's own sealed hierarchy
 *       permits. Every one of these MUST have a YAML binding: the processor
 *       can legitimately return any of them directly, so a missing binding
 *       here is always an error, regardless of mode severity.</li>
 *   <li><b>registry</b> — the frozen, boot-time closed-world scan of every
 *       concrete RouteOutcome implementation in the base package. A YAML key
 *       that isn't in declaredOutcomes is still legitimate if it resolves
 *       against this registry — that's the case for a Split/Multicast
 *       destination that isn't part of this processor's own sealed
 *       hierarchy (e.g. a shared, cross-cutting outcome type). A YAML key
 *       found in neither is a genuine typo or an unresolvable reference.</li>
 * </ul>
 *
 * <pre>
 * STRICT     — missing OR unresolved bindings → startup failure
 * PERMISSIVE — missing bindings → failure; unresolved bindings → warn + ignore
 * SILENT     — missing bindings → warn only; unresolved bindings → ignore silently
 * </pre>
 */
public class BindingValidator {

    private static final Logger log = LoggerFactory.getLogger(BindingValidator.class);

    private final ValidationMode mode;

    public BindingValidator(ValidationMode mode) {
        this.mode = mode;
        log.info("Binding validator initialized in {} mode", mode);
    }

    public void validate(String processorName, Set<String> declaredOutcomes, RouteConfig routeConfig,
                          RouteOutcomeRegistry registry) {
        Map<String, List<String>> bindings = routeConfig.getBindings();

        if (bindings == null || bindings.isEmpty()) {
            String msg = String.format(
                "[%s] No bindings declared in routes.yaml. " +
                "Expected bindings for: %s", processorName, declaredOutcomes);
            handleMissing(msg);
            return;
        }

        Set<String> configuredKeys = bindings.keySet();

        // Outcomes the processor's own sealed hierarchy permits, but missing
        // from YAML — always an error, since the processor can legitimately
        // return these directly regardless of any Split/Multicast usage.
        Set<String> missingBindings = declaredOutcomes.stream()
            .filter(o -> !configuredKeys.contains(o))
            .collect(Collectors.toSet());

        // YAML keys outside this processor's sealed hierarchy. Legitimate if
        // they resolve against the frozen closed-world registry (a real
        // RouteOutcome implementation exists somewhere in the scanned
        // package) — that's the Split/Multicast cross-cutting case.
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
}