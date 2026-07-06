package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that YAML bindings match the sealed interface topology declared
 * by a processor class, according to the configured {@link ValidationMode}.
 *
 * <pre>
 * STRICT     — missing OR extra bindings → startup failure
 * PERMISSIVE — missing bindings → failure; extra bindings → warn + ignore
 * SILENT     — missing bindings → warn only; extra bindings → ignore silently
 * </pre>
 */
public class BindingValidator {

    private static final Logger log = LoggerFactory.getLogger(BindingValidator.class);

    private final ValidationMode mode;

    public BindingValidator(ValidationMode mode) {
        this.mode = mode;
        log.info("Binding validator initialized in {} mode", mode);
    }

    /**
     * Validate the bindings in routeConfig against the declared outcomes
     * extracted from the processor's sealed interface.
     *
     * @param processorName simple class name of the processor (for error messages)
     * @param declaredOutcomes sealed permits simple names
     * @param routeConfig the parsed YAML config for this processor
     */
    public void validate(String processorName, Set<String> declaredOutcomes, RouteConfig routeConfig) {
        Map<String, List<String>> bindings = routeConfig.getBindings();

        if (bindings == null || bindings.isEmpty()) {
            String msg = String.format(
                "[%s] No bindings declared in routes.yaml. " +
                "Expected bindings for: %s", processorName, declaredOutcomes);
            handleMissing(msg);
            return;
        }

        Set<String> configuredKeys = bindings.keySet();

        // outcomes declared in code but missing from YAML
        Set<String> missingBindings = declaredOutcomes.stream()
            .filter(o -> !configuredKeys.contains(o))
            .collect(Collectors.toSet());

        // bindings in YAML that don't match any declared outcome
        Set<String> extraBindings = configuredKeys.stream()
            .filter(k -> !declaredOutcomes.contains(k))
            .collect(Collectors.toSet());

        if (!missingBindings.isEmpty()) {
            String msg = String.format(
                "[%s] Missing YAML bindings for route outcomes declared in code: %s. " +
                "Add these to routes.yaml under '%s.bindings'.",
                processorName, missingBindings, processorName);
            handleMissing(msg);
        }

        if (!extraBindings.isEmpty()) {
            String msg = String.format(
                "[%s] YAML declares bindings for unknown route outcomes: %s. " +
                "These don't match any permitted subtype of the route interface. " +
                "Check for typos or stale config.",
                processorName, extraBindings);
            handleExtra(msg);
        }

        if (missingBindings.isEmpty() && extraBindings.isEmpty()) {
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
            case PERMISSIVE -> log.warn("PERMISSIVE MODE — ignoring extra binding. {}", message);
            case SILENT -> {} 
        }
    }
}
