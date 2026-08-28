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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

public final class GuanacoTestSupport {

    private final String basePackage;
    private final Map<String, RouteConfig> virtualRoutes = new HashMap<>();
    private ValidationMode validationMode = ValidationMode.STRICT;

    private GuanacoThrottlerConfig routeThrottler;
    private GuanacoCircuitBreakerConfig routeCircuitBreaker;
    private GuanacoDelayerConfig routeDelayer;
    private GuanacoAggregateConfig routeAggregate;
    private GuanacoIdempotentConfig routeIdempotent;
    private GuanacoResequenceConfig routeResequence;

    private final Map<String, GuanacoDelayStrategy> delayStrategies = new HashMap<>();
    private final Map<String, AggregationStrategy> aggregationStrategies = new HashMap<>();

    private GuanacoContext context;

    public GuanacoTestSupport(String basePackage) {
        this.basePackage = basePackage;
    }

    public GuanacoTestSupport withValidation(ValidationMode mode) {
        this.validationMode = mode;
        return this;
    }

    /**
     * Sets the route-level throttler default applied to the next {@code .route(...)} call and then cleared.
     *
     * @param throttler the throttler configuration to apply
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteThrottler(GuanacoThrottlerConfig throttler) {
        this.routeThrottler = throttler;
        return this;
    }

    /**
     * Sets the route-level circuit breaker default applied to the next {@code .route(...)} call and then cleared.
     *
     * @param cb the circuit breaker configuration to apply
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteCircuitBreaker(GuanacoCircuitBreakerConfig cb) {
        this.routeCircuitBreaker = cb;
        return this;
    }

    /**
     * Sets the route-level delayer default applied to the next {@code .route(...)} call and then cleared.
     *
     * @param delayer the delayer configuration to apply
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteDelayer(GuanacoDelayerConfig delayer) {
        this.routeDelayer = delayer;
        return this;
    }

    /**
     * Sets the aggregate config applied to the next {@code .route(...)} call and then cleared.
     *
     * @param aggregate the aggregate configuration to apply
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteAggregate(GuanacoAggregateConfig aggregate) {
        this.routeAggregate = aggregate;
        return this;
    }

    /**
     * Sets the idempotent config applied to the next {@code .route(...)} call and then cleared.
     *
     * @param idempotent the idempotent configuration to apply
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteIdempotent(GuanacoIdempotentConfig idempotent) {
        this.routeIdempotent = idempotent;
        return this;
    }

    /**
     * Sets the resequence config applied to the next {@code .route(...)} call and then cleared.
     *
     * @param resequence the resequence configuration to apply
     * @return this GuanacoTestSupport builder instance
     */
    public GuanacoTestSupport withRouteResequence(GuanacoResequenceConfig resequence) {
        this.routeResequence = resequence;
        return this;
    }

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