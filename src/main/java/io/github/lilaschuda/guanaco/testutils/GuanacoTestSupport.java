package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoConfig;
import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.GuanacoDelayerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThrottlerConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.core.GuanacoContext;
import io.github.lilaschuda.guanaco.core.GuanacoDelayStrategy;
import io.github.lilaschuda.guanaco.core.GuanacoRouteBuilderException;

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
    private GuanacoContext context;

    private final Map<String, GuanacoDelayStrategy> delayStrategies = new HashMap<>();
    private GuanacoDelayerConfig routeDelayer;

    public GuanacoTestSupport(String basePackage) {
        this.basePackage = basePackage;
    }

    public GuanacoTestSupport withValidation(ValidationMode mode) {
        this.validationMode = mode;
        return this;
    }

    /** Route-level throttler default — applied to the next .route(...) call, then cleared. */
    public GuanacoTestSupport withRouteThrottler(GuanacoThrottlerConfig throttler) {
        this.routeThrottler = throttler;
        return this;
    }

    /** Route-level circuit breaker default — applied to the next .route(...) call, then cleared. */
    public GuanacoTestSupport withRouteCircuitBreaker(GuanacoCircuitBreakerConfig cb) {
        this.routeCircuitBreaker = cb;
        return this;
    }

    public GuanacoTestSupport route(String processorName, String fromUri, Map<String, List<BindingTarget>> bindings) {
        RouteConfig routeConfig = new RouteConfig();
        routeConfig.setFrom(fromUri);
        routeConfig.setThrottler(routeThrottler);
        routeConfig.setCircuitBreaker(routeCircuitBreaker);

        Map<String, List<BindingTarget>> rawBindings = new HashMap<>();
        bindings.forEach(rawBindings::put);
        routeConfig.setBindings(rawBindings);

        virtualRoutes.put(processorName, routeConfig);

        // Cleared after use so a second .route(...) call in the same test
        // doesn't silently inherit the previous route's policy defaults.
        this.routeThrottler = null;
        this.routeCircuitBreaker = null;

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
        delayStrategies.forEach((k,v) -> {
            context.getDelayStrategies().put(k, v);
        });
        context.wireRoutes();
        context.start();
        return new GuanacoRuntimeEnvironment(context);
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

    public GuanacoTestSupport withRouteDelayer(GuanacoDelayerConfig delayer) {
        this.routeDelayer = delayer;
        return this;
    }
}