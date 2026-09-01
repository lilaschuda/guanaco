package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.context.exception.BindingValidationException;
import io.github.lilaschuda.guanaco.context.exception.ForbiddenComponentException;
import io.github.lilaschuda.guanaco.context.exception.InvalidRouteConfigurationException;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.GuanacoDelayerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
import io.github.lilaschuda.guanaco.config.GuanacoSampleConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThreadsConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThrottlerConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates route configurations against declared route outcomes, EIP parameters,
 * and forbidden component schemes.
 */
class BindingValidator {

    private static final Logger log = LoggerFactory.getLogger(BindingValidator.class);

    private static final Set<String> FORBIDDEN_SCHEMES = Set.of(
            "language", "groovy", "js", "javascript", "mvel", "ognl", "python"
    );

    private static final String CONTROLBUS_SCHEME = "controlbus";
    // LinkedHashSet, not Set.of() -- Set.of()'s iteration order is
    // deliberately unspecified/randomized per JVM run, which would make
    // this exact error message read differently across restarts.
    private static final Set<String> CONTROLBUS_ALLOWED_ACTIONS = new LinkedHashSet<>(List.of(
            "start", "stop", "suspend", "resume", "status"
    ));

    private final ValidationMode mode;

    /**
     * Constructs a validator operating under the specified validation mode.
     *
     * @param mode the mode governing validation behavior for missing or extra bindings
     */
    public BindingValidator(ValidationMode mode) {
        this.mode = mode;
        log.info("Binding validator initialized in {} mode", mode);
    }

    /**
     * Validates that bindings declared in configuration match the outcomes declared in code.
     *
     * @param processorName the name of the processor being validated
     * @param declaredOutcomes the set of simple outcome names declared by the processor
     * @param routeConfig the route configuration loaded for this processor
     * @param registry the boot-time registry of concrete {@link RouteOutcome} classes
     */
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
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @param routeInterface the route interface implemented by the processor
     * @throws InvalidRouteConfigurationException if a circuit breaker override is invalidly declared
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

    /**
     * Validates circuit breaker settings across route-level and binding-level overrides.
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @throws InvalidRouteConfigurationException if circuit breaker configuration values are invalid
     */
    public void validateCircuitBreakerConfig(String processorName, RouteConfig routeConfig) {
        validateCircuitBreakerShape(processorName, "circuitBreaker", routeConfig.getCircuitBreaker());

        for (Map.Entry<String, List<BindingTarget>> entry : routeConfig.getBindings().entrySet()) {
            for (BindingTarget target : entry.getValue()) {
                if (target.getCircuitBreaker() != null) {
                    validateCircuitBreakerShape(processorName, "bindings." + entry.getKey() + ".circuitBreaker", target.getCircuitBreaker());
                }
            }
        }
    }

    private void validateCircuitBreakerShape(String processorName, String fieldDescription, GuanacoCircuitBreakerConfig cb) {
        if (cb == null || !cb.resolveEnabled()) {
            return;
        }
        if (cb.getWaitDurationInOpenStateMs() != null && cb.getWaitDurationInOpenStateMs() <= 0) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + ".waitDurationInOpenStateMs must be greater than zero if specified.");
        }
    }

    /**
     * Validates aggregate EIP configuration parameters.
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @throws InvalidRouteConfigurationException if required fields are missing or invalid
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
                    "[" + processorName + "] aggregate block must declare at least one completion "
                    + "condition greater than zero: completionSize or completionTimeoutMs.");
        }

        log.info("[{}] Aggregate config validated OK — correlationHeader='{}', strategyRef='{}'",
                processorName, agg.getCorrelationHeader(), agg.getStrategyRef());
    }

    /**
     * Validates idempotent consumer EIP configuration parameters.
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @throws InvalidRouteConfigurationException if configuration options are invalid
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

    /**
     * Validates resequence EIP configuration parameters.
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @throws InvalidRouteConfigurationException if resequence parameters are inconsistent or invalid
     */
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

    /**
     * Validates that no declared endpoint URI (the route's {@code from},
     * or any binding target) uses a forbidden scripting scheme, and that
     * any {@code controlbus:} URI stays within the supported route
     * lifecycle mode (see {@link #checkControlBus}).
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @throws io.github.lilaschuda.guanaco.context.exception.ForbiddenComponentException
     *         if a forbidden scripting scheme or controlbus:language: mode is found
     * @throws InvalidRouteConfigurationException if a controlbus:route URI is missing or has an invalid action
     */
    public void validateNoForbiddenSchemes(String processorName, RouteConfig routeConfig) {
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
     * Validates that no per-binding DSL-only policy (circuitBreaker, throttler, delayer, sample)
     * is declared on an outcome that isn't a permitted subtype of the
     * processor's sealed hierarchy.
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @param routeInterface the route interface implemented by the processor
     * @throws InvalidRouteConfigurationException if a DSL policy override is invalidly declared
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
                if (target.getSample() != null) {
                    throw scopeViolation(processorName, entry.getKey(), "sample", routeInterface);
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
     * Validates the structural shape of throttler configuration blocks.
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @throws InvalidRouteConfigurationException if throttler parameters are incomplete or contradictory
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

    /**
     * Validates the structural shape of delayer configuration blocks.
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @throws InvalidRouteConfigurationException if delayer parameters are incomplete or ambiguous
     */
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

    /**
     * Validates the structural shape of sample configuration blocks, at
     * both the route level (ingress) and binding level (egress). Unlike
     * throttler/delayer/circuitBreaker, these two levels are validated
     * completely independently — there is no inheritance, so a binding's
     * sample block is checked purely on its own terms, never in relation
     * to the route-level one.
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @throws InvalidRouteConfigurationException if sample parameters are incomplete or contradictory
     */
    public void validateSampleConfig(String processorName, RouteConfig routeConfig) {
        validateSampleShape(processorName, "sample", routeConfig.getSample());

        for (Map.Entry<String, List<BindingTarget>> entry : routeConfig.getBindings().entrySet()) {
            for (BindingTarget target : entry.getValue()) {
                if (target.getSample() != null) {
                    validateSampleShape(processorName, "bindings." + entry.getKey() + ".sample", target.getSample());
                }
            }
        }
    }

    private void validateSampleShape(String processorName, String fieldDescription, GuanacoSampleConfig sample) {
        if (sample == null) {
            return;
        }

        boolean hasFrequency = sample.getMessageFrequency() != null;
        boolean hasPeriod = sample.getSamplePeriodMillis() != null;

        if (hasFrequency && hasPeriod) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + " sets both messageFrequency and "
                    + "samplePeriodMillis — these are alternative sample modes. Set exactly one.");
        }

        if (!hasFrequency && !hasPeriod) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + " must set exactly one of messageFrequency "
                    + "or samplePeriodMillis.");
        }

        if (hasFrequency && sample.getMessageFrequency() <= 0) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + ".messageFrequency must be greater than zero.");
        }

        if (hasPeriod && sample.getSamplePeriodMillis() <= 0) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + ".samplePeriodMillis must be greater than zero.");
        }
    }

    /**
     * Validates the route-level thread handoff configuration, if present.
     * Unlike {@link #validateSampleShape}/delayer's shape validation, this
     * is mutual exclusion rather than a required choice: an entirely empty
     * {@code GuanacoThreadsConfig} is valid (plain {@code .threads()} with
     * Camel's own defaults) — only combining {@code executorServiceRef}
     * with an inline pool field is an error.
     *
     * @param processorName the name of the processor being validated
     * @param routeConfig the route configuration loaded for this processor
     * @throws InvalidRouteConfigurationException if executorServiceRef is combined with an inline pool field,
     *         or an inline pool size is not positive, or maxPoolSize is smaller than poolSize
     */
    public void validateThreadsConfig(String processorName, RouteConfig routeConfig) {
        GuanacoThreadsConfig threads = routeConfig.getThreads();
        if (threads == null) {
            return;
        }

        boolean hasInlineField = threads.getPoolSize() != null
                || threads.getMaxPoolSize() != null
                || threads.getThreadName() != null
                || threads.getRejectedPolicy() != null
                || threads.getCallerRunsWhenRejected() != null;
        boolean hasRef = threads.getExecutorServiceRef() != null;

        if (hasRef && hasInlineField) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] threads sets executorServiceRef together with an inline pool field "
                    + "(poolSize/maxPoolSize/threadName/rejectedPolicy/callerRunsWhenRejected) — these are "
                    + "alternative pool sources. Set executorServiceRef alone, or inline fields alone (or "
                    + "neither, to use Camel's own default pool).");
        }

        if (threads.getPoolSize() != null && threads.getPoolSize() <= 0) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] threads.poolSize must be greater than zero.");
        }

        if (threads.getMaxPoolSize() != null && threads.getMaxPoolSize() <= 0) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] threads.maxPoolSize must be greater than zero.");
        }

        if (threads.getPoolSize() != null && threads.getMaxPoolSize() != null
                && threads.getMaxPoolSize() < threads.getPoolSize()) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] threads.maxPoolSize (" + threads.getMaxPoolSize()
                    + ") must not be smaller than threads.poolSize (" + threads.getPoolSize() + ").");
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

        if (CONTROLBUS_SCHEME.equals(scheme)) {
            checkControlBus(processorName, fieldDescription, uri);
        }
    }

    /**
     * Guanaco's scripting guardrail above matches only the top-level Camel
     * component scheme, so {@code controlbus:language:...} — a completely
     * different Camel mode that executes an arbitrary expression against
     * the CamelContext — would otherwise sail straight past it, laundered
     * through a component name that isn't on the forbidden list. Closed
     * here, specifically for controlbus: URIs.
     *
     * <p>Also enforces that {@code controlbus:route} URIs (the only other
     * supported mode) carry a present, recognized {@code action} —
     * Camel's own {@code ControlBusProducer} silently no-ops at runtime if
     * neither {@code action} nor {@code language} is set, so a missing
     * action would otherwise compile cleanly and just do nothing.
     */
    private void checkControlBus(String processorName, String fieldDescription, String uri) {
        String remaining = uri.substring(CONTROLBUS_SCHEME.length() + 1);
        int queryIdx = remaining.indexOf('?');
        String path = queryIdx >= 0 ? remaining.substring(0, queryIdx) : remaining;
        String query = queryIdx >= 0 ? remaining.substring(queryIdx + 1) : "";

        if (path.startsWith("language:")) {
            String msg = String.format(
                    "[%s] Forbidden scripting mode 'controlbus:%s' found in %s ('%s'). "
                    + "controlbus:language:... executes an arbitrary expression against the CamelContext — "
                    + "exactly what Guanaco's scripting guardrail exists to prevent, reached here through a "
                    + "different component name. Only controlbus:route?routeId=...&action=... "
                    + "(route lifecycle management) is permitted.",
                    processorName, path, fieldDescription, uri);
            log.error(msg);
            throw new ForbiddenComponentException(msg);
        }

        if (!"route".equals(path)) {
            boolean looksLikeTypo = path.equalsIgnoreCase("route") || path.equalsIgnoreCase("routes");
            String hint = looksLikeTypo
                    ? " Did you mean 'controlbus:route'? (mode names are case-sensitive, and it's singular.)"
                    : "";
            String msg = String.format(
                    "[%s] Unsupported controlbus mode '%s' found in %s ('%s')." + hint + " "
                    + "Guanaco only supports controlbus:route?routeId=...&action=... for route lifecycle management.",
                    processorName, path, fieldDescription, uri);
            log.error(msg);
            throw new ForbiddenComponentException(msg);
        }

        String action = extractQueryParam(query, "action");
        if (action == null) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + " ('" + uri + "') is missing a required "
                    + "'action' parameter. Without one, controlbus:route silently does nothing at runtime — "
                    + "Camel's own ControlBusProducer just no-ops when neither action nor language is set. "
                    + "Allowed actions: " + CONTROLBUS_ALLOWED_ACTIONS + ".");
        }
        if (!CONTROLBUS_ALLOWED_ACTIONS.contains(action)) {
            throw new InvalidRouteConfigurationException(
                    "[" + processorName + "] " + fieldDescription + " ('" + uri + "') has action='" + action
                    + "', which is not a recognized route lifecycle action. Allowed: "
                    + CONTROLBUS_ALLOWED_ACTIONS + ".");
        }
    }

    private String extractQueryParam(String query, String key) {
        if (query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(k)) {
                return eq >= 0 ? pair.substring(eq + 1) : "";
            }
        }
        return null;
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