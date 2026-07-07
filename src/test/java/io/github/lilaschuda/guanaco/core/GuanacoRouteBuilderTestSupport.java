package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared JUnit 5 lifecycle and construction helpers for tests that exercise
 * GuanacoRouteBuilder directly, bypassing GuanacoContext's classpath scanning,
 * TopologyInspector, and BindingValidator. Intended for isolated tests of
 * routing/EIP behavior where the processor and RouteConfig are constructed
 * by hand rather than discovered from routes.yaml.
 *
 * Subclasses get a fresh, unstarted CamelContext per test via {@link #context},
 * automatically stopped after each test.
 */
public abstract class GuanacoRouteBuilderTestSupport {

    /**
     * RouteOutcome.class as a raw Class object, widened to the wildcard-bounded
     * type GuanacoRouteBuilder's constructor expects. Java's wildcard capture
     * rules don't consider Class<RouteOutcome> and Class<? extends RouteOutcome<?>>
     * directly assignment-compatible, even though the relationship trivially
     * holds — this is the same friction documented elsewhere in this codebase
     * around Class<? extends X<?>> boundaries. Use this constant for routes
     * that never reach the choice() dispatch table (e.g. Drop/Multicast-only
     * processors); for routes that do reach it (e.g. Split), pass a real
     * sealed interface's Class instead.
     */
    @SuppressWarnings("unchecked")
    protected static final Class<? extends RouteOutcome<?>> ROUTE_OUTCOME_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) RouteOutcome.class;

    protected CamelContext context;

    @BeforeEach
    void setUpGuanacoRouteBuilderTestSupport() {
        context = new DefaultCamelContext();
    }

    @AfterEach
    void tearDownGuanacoRouteBuilderTestSupport() {
        context.stop();
    }

    /**
     * Registers a GuanacoRouteBuilder route directly. Caller is responsible
     * for calling context.start() afterward.
     */
    protected void registerRoute(
            Processor<? extends RouteOutcome<?>> processor,
            Class<? extends RouteOutcome<?>> routeInterface,
            RouteConfig config,
            String processorName) throws Exception {
        context.addRoutes(new GuanacoRouteBuilder(processor, routeInterface, config, processorName));
    }

    /** Convenience overload for routes that never reach the choice() table (Drop/Multicast-only). */
    protected void registerRoute(
            Processor<? extends RouteOutcome<?>> processor,
            RouteConfig config,
            String processorName) throws Exception {
        registerRoute(processor, ROUTE_OUTCOME_CLASS, config, processorName);
    }

    /** Builds a RouteConfig with a single endpoint bound per outcome name, no error handler. */
    protected RouteConfig routeConfig(String from, Map<String, String> singleBindings) {
        RouteConfig config = new RouteConfig();
        config.setFrom(from);

        Map<String, Object> raw = new LinkedHashMap<>();
        singleBindings.forEach(raw::put);
        config.setBindings(raw);

        return config;
    }

    /** Builds a RouteConfig with a single endpoint bound per outcome name, plus a dead letter. */
    protected RouteConfig routeConfigWithDeadLetter(String from, Map<String, String> singleBindings, String deadLetter) {
        RouteConfig config = routeConfig(from, singleBindings);

        RouteConfig.ErrorHandlerConfig errorHandler = new RouteConfig.ErrorHandlerConfig();
        errorHandler.setDeadLetter(deadLetter);
        config.setErrorHandler(errorHandler);

        return config;
    }
}