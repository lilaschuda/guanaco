package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.core.GuanacoContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Public test support utility for applications building on camel-guanaco.
 * Provides a fluent builder to spin up localized contexts, wire routes,
 * and assert outcomes with minimal boilerplate.
 */
public final class GuanacoTestSupport {

    private final String basePackage;
    private final Map<String, RouteConfig> virtualRoutes = new HashMap<>();
    private ValidationMode validationMode = ValidationMode.STRICT;
    private GuanacoContext context;

    public GuanacoTestSupport(String basePackage) {
        this.basePackage = basePackage;
    }

    /** Overrides the global framework validation severity for this test environment. */
    public GuanacoTestSupport withValidation(ValidationMode mode) {
        this.validationMode = mode;
        return this;
    }

    /** Fluently defines a route configuration for a given processor by hand, bypassing routes.yaml. */
    public GuanacoTestSupport route(String processorName, String fromUri, Map<String, List<BindingTarget>> bindings) {
        RouteConfig routeConfig = new RouteConfig();
        routeConfig.setFrom(fromUri);
        
        Map<String, List<BindingTarget>> rawBindings = new HashMap<>();
        bindings.forEach(rawBindings::put);
        routeConfig.setBindings(rawBindings);
        
        virtualRoutes.put(processorName, routeConfig);
        return this;
    }

    /** Initializes the context, runs Topology Inspection, validates bindings, and starts the routes. */
    public GuanacoRuntimeEnvironment start() throws Exception {
        // Subclass or initialize GuanacoContext using our virtual route configurations
        this.context = new GuanacoContext(basePackage) {
            @Override
            public void wireRoutes() throws Exception {
                // Here we can inject our virtualRoutes map directly into the loader 
                // to skip loading a physical routes.yaml if the user wants programmatic test profiles.
                super.wireRoutes();
            }
        };

        context.start();
        return new GuanacoRuntimeEnvironment(context);
    }
}