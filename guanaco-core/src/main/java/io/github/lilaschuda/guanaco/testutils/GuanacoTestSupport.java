package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoConfig;
import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.GuanacoDelayerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThrottlerConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.context.GuanacoContext;
import io.github.lilaschuda.guanaco.api.GuanacoDelayStrategy;
import org.apache.camel.AggregationStrategy;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

/**
 * Fluent test utility for bootstrapping isolated Guanaco runtime environments
 * with virtual route configurations, mock bindings, and registered strategies.
 */
public final class GuanacoTestSupport {

    private final String basePackage;
    private final Map<String, RouteConfig> virtualRoutes = new HashMap<>();
    private ValidationMode validationMode = ValidationMode.STRICT;

    private @Nullable GuanacoThrottlerConfig routeThrottler;
    private @Nullable GuanacoCircuitBreakerConfig routeCircuitBreaker;
    private @Nullable GuanacoDelayerConfig routeDelayer;
    private @Nullable GuanacoAggregateConfig routeAggregate;
    private @Nullable GuanacoIdempotentConfig routeIdempotent;
    private @Nullable GuanacoResequenceConfig routeResequence;

    private final Map<String, GuanacoDelayStrategy> delayStrategies = new HashMap<>();
    private final Map<String, AggregationStrategy> aggregationStrategies = new HashMap<>();

    private @Nullable GuanacoContext context;

    /**
     * Constructs a test support builder for the specified package path.
     *
     * @param basePackage package path to scan for Guanaco processor components
     */
    public GuanacoTestSupport(String basePackage) {
        this.basePackage = basePackage;
    }

    /**
     * Configures the validation mode for the test environment.
     *
     * @param mode the validation mode to apply (e.g., {@code STRICT} or {@code PERMISSIVE}),
     *             or {@code null} to reset to the default ({@code STRICT})
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withValidation(@Nullable ValidationMode mode) {
        this.validationMode = mode != null ? mode : ValidationMode.STRICT;
        return this;
    }

    /**
     * Sets the route-level throttler default applied to the next {@code .route(...)} call and then cleared.
     *
     * @param throttler the throttler configuration to apply, or {@code null} to clear a previously-set one
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteThrottler(@Nullable GuanacoThrottlerConfig throttler) {
        this.routeThrottler = throttler;
        return this;
    }

    /**
     * Sets the route-level circuit breaker default applied to the next {@code .route(...)} call and then cleared.
     *
     * @param cb the circuit breaker configuration to apply, or {@code null} to clear a previously-set one
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteCircuitBreaker(@Nullable GuanacoCircuitBreakerConfig cb) {
        this.routeCircuitBreaker = cb;
        return this;
    }

    /**
     * Sets the route-level delayer default applied to the next {@code .route(...)} call and then cleared.
     *
     * @param delayer the delayer configuration to apply, or {@code null} to clear a previously-set one
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteDelayer(@Nullable GuanacoDelayerConfig delayer) {
        this.routeDelayer = delayer;
        return this;
    }

    /**
     * Sets the aggregate config applied to the next {@code .route(...)} call and then cleared.
     *
     * @param aggregate the aggregate configuration to apply, or {@code null} to clear a previously-set one
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteAggregate(@Nullable GuanacoAggregateConfig aggregate) {
        this.routeAggregate = aggregate;
        return this;
    }

    /**
     * Sets the idempotent config applied to the next {@code .route(...)} call and then cleared.
     *
     * @param idempotent the idempotent configuration to apply, or {@code null} to clear a previously-set one
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteIdempotent(@Nullable GuanacoIdempotentConfig idempotent) {
        this.routeIdempotent = idempotent;
        return this;
    }

    /**
     * Sets the resequence config applied to the next {@code .route(...)} call and then cleared.
     *
     * @param resequence the resequence configuration to apply, or {@code null} to clear a previously-set one
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteResequence(@Nullable GuanacoResequenceConfig resequence) {
        this.routeResequence = resequence;
        return this;
    }

    /**
     * Registers a virtual route configuration with the given processor name, entry URI, and outcome bindings.
     *
     * @param processorName target processor class/bean name
     * @param fromUri Camel {@code from} endpoint URI
     * @param bindings mapping of outcome class names to lists of target bindings
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport route(String processorName, String fromUri, Map<String, List<BindingTarget>> bindings) {
        RouteConfig routeConfig = new RouteConfig();
        routeConfig.setFrom(fromUri);
        routeConfig.setThrottler(routeThrottler);
        routeConfig.setCircuitBreaker(routeCircuitBreaker);
        routeConfig.setDelayer(routeDelayer);
        routeConfig.setAggregate(routeAggregate);
        routeConfig.setIdempotent(routeIdempotent);
        routeConfig.setResequence(routeResequence);

        Map<String, List<BindingTarget>> rawBindings = new HashMap<>();
        bindings.forEach(rawBindings::put);
        routeConfig.setBindings(rawBindings);

        virtualRoutes.put(processorName, routeConfig);

        // Cleared after use so a second .route(...) call in the same test
        // doesn't silently inherit the previous route's policy/EIP defaults.
        this.routeThrottler = null;
        this.routeCircuitBreaker = null;
        this.routeDelayer = null;
        this.routeAggregate = null;
        this.routeIdempotent = null;
        this.routeResequence = null;

        return this;
    }

    /**
     * Registers a custom delay strategy under the specified name.
     *
     * @param name unique identifier for the delay strategy
     * @param strategy strategy implementation instance
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport registerDelayStrategy(String name, GuanacoDelayStrategy strategy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("GuanacoDelayStrategy name must be provided and non-blank.");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("GuanacoDelayStrategy instance must not be null.");
        }

        GuanacoDelayStrategy previous = delayStrategies.putIfAbsent(name, strategy);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "A GuanacoDelayStrategy is already registered under name '" + name + "'. "
                    + "Registration is explicit and must be unique — choose a different name.");
        }

        return this;
    }

    /**
     * Registers a custom Camel aggregation strategy under the specified name.
     *
     * @param name unique identifier for the aggregation strategy
     * @param strategy aggregation strategy implementation instance
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport registerAggregationStrategy(String name, AggregationStrategy strategy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("AggregationStrategy name must be provided and non-blank.");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("AggregationStrategy instance must not be null.");
        }

        AggregationStrategy previous = aggregationStrategies.putIfAbsent(name, strategy);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "An AggregationStrategy is already registered under name '" + name + "'. "
                    + "Registration is explicit and must be unique — choose a different name.");
        }

        return this;
    }

    /**
     * Initializes and starts the Guanaco runtime environment with all registered virtual routes and strategies.
     *
     * @return active runtime environment wrapping the started context
     * @throws Exception if context creation or lifecycle startup fails
     */
    public GuanacoRuntimeEnvironment start() throws Exception {
        this.context = new GuanacoContext(basePackage) {
            @Override
            protected GuanacoConfig loadConfig() {
                GuanacoConfig config = new GuanacoConfig();
                GuanacoConfig.FrameworkConfig framework = new GuanacoConfig.FrameworkConfig();
                framework.setValidation(validationMode);
                config.setFramework(framework);
                config.setRoutes(virtualRoutes);
                return config;
            }
        };

        ApplicationContext ctx = new StaticApplicationContext();
        context.setApplicationContext(ctx);

        // Goes through GuanacoContext's own registerX(...) guards — no direct
        // map access, so the null/blank/duplicate checks can't be bypassed.
        delayStrategies.forEach(context::registerDelayStrategy);
        aggregationStrategies.forEach(context::registerAggregationStrategy);

        context.wireRoutes();
        context.start();
        return new GuanacoRuntimeEnvironment(context);
    }
}