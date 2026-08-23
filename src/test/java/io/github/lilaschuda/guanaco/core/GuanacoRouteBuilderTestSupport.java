package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class GuanacoRouteBuilderTestSupport {

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

    protected void registerRoute(
            Processor<? extends RouteOutcome<?>> processor,
            Class<? extends RouteOutcome<?>> routeInterface,
            RouteConfig config,
            String processorName,
            RouteOutcomeRegistry registry) throws Exception {
        registerRoute(processor, routeInterface, config, processorName, registry, Map.of(), Map.of());
    }

    protected void registerRoute(
            Processor<? extends RouteOutcome<?>> processor,
            Class<? extends RouteOutcome<?>> routeInterface,
            RouteConfig config,
            String processorName,
            RouteOutcomeRegistry registry,
            Map<String, AggregationStrategy> aggregationStrategies,
            Map<String, GuanacoDelayStrategy> delayStrategies) throws Exception {
        context.addRoutes(new GuanacoRouteBuilder(
                processor, routeInterface, config, processorName, registry, aggregationStrategies, delayStrategies));
    }

    protected void registerRoute(
            Processor<? extends RouteOutcome<?>> processor,
            RouteConfig config,
            String processorName,
            RouteOutcomeRegistry registry) throws Exception {
        registerRoute(processor, ROUTE_OUTCOME_CLASS, config, processorName, registry, Map.of(), Map.of());
    }

    /**
     * Builds a RouteConfig with a single plain-URI (no circuit breaker)
     * binding target per outcome name. Internally builds the
     * Map&lt;String, List&lt;BindingTarget&gt;&gt; shape RouteConfig now
     * requires, but keeps this method's own signature unchanged from before
     * the v0.5.0 BindingTarget refactor — every existing test call site
     * (Drop/Multicast/Split/Aggregate/Idempotent/Resequence) keeps working
     * without modification.
     */
    protected RouteConfig routeConfig(String from, Map<String, String> singleBindings) {
        RouteConfig config = new RouteConfig();
        config.setFrom(from);
        config.setBindings(toBindingTargets(singleBindings));
        return config;
    }

    protected RouteConfig routeConfigWithDeadLetter(String from, Map<String, String> singleBindings, String deadLetter) {
        RouteConfig config = routeConfig(from, singleBindings);

        RouteConfig.ErrorHandlerConfig errorHandler = new RouteConfig.ErrorHandlerConfig();
        errorHandler.setDeadLetter(deadLetter);
        config.setErrorHandler(errorHandler);

        return config;
    }

    /**
     * Like routeConfig(...), but allows attaching a circuit breaker
     * override to one specific outcome's binding, for Circuit Breaker
     * tests. All other outcomes get plain URI-only targets, same as
     * routeConfig(...).
     */
    protected RouteConfig routeConfigWithCircuitBreaker(
            String from, Map<String, String> singleBindings,
            String circuitBreakerOutcomeName, io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig cb) {

        RouteConfig config = routeConfig(from, singleBindings);

        List<BindingTarget> targets = config.getBindings().get(circuitBreakerOutcomeName);
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "routeConfigWithCircuitBreaker: '" + circuitBreakerOutcomeName +
                    "' is not present in singleBindings — add it first.");
        }
        targets.get(0).setCircuitBreaker(cb);

        return config;
    }

    private Map<String, List<BindingTarget>> toBindingTargets(Map<String, String> singleBindings) {
        Map<String, List<BindingTarget>> result = new LinkedHashMap<>();
        singleBindings.forEach((outcomeName, uri) -> {
            BindingTarget target = new BindingTarget();
            target.setUri(uri);
            List<BindingTarget> list = new ArrayList<>();
            list.add(target);
            result.put(outcomeName, list);
        });
        return result;
    }
}