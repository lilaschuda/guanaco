package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuanacoRouteBuilderAggregateTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToMerged {}
    record ToMerged(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    @Test
    void completedAggregate_deliversOneMergedMessageAfterCompletionSize() throws Exception {
        // Concatenates each incoming body, comma-separated.
        AggregationStrategy concatStrategy = (oldExchange, newExchange) -> {
            if (oldExchange == null) return newExchange;
            String oldBody = oldExchange.getIn().getBody(String.class);
            String newBody = newExchange.getIn().getBody(String.class);
            newExchange.getIn().setBody(oldBody + "," + newBody);
            return newExchange;
        };

        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToMerged.class);

        RouteConfig config = routeConfig("direct:orders", Map.of("ToMerged", "mock:merged"));
        GuanacoAggregateConfig agg = new GuanacoAggregateConfig();
        agg.setCorrelationHeader("orderId");
        agg.setStrategyRef("concat");
        agg.setCompletionSize(2);
        config.setAggregate(agg);

        Processor<RouteOutcome<?>> processor = exchange ->
                new ToMerged(exchange.getIn().getBody(String.class));

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "AggregateTest", registry,
                Map.of("concat", concatStrategy));
        context.start();

        MockEndpoint merged = context.getEndpoint("mock:merged", MockEndpoint.class);
        merged.expectedMessageCount(1); // one release, not two individual messages
        merged.expectedBodiesReceived("first,second");

        ProducerTemplate producer = context.createProducerTemplate();
        producer.send("direct:orders", exchange -> {
            exchange.getIn().setHeader("orderId", "order-1");
            exchange.getIn().setBody("first");
        });
        producer.send("direct:orders", exchange -> {
            exchange.getIn().setHeader("orderId", "order-1");
            exchange.getIn().setBody("second");
        });

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void unresolvedStrategyRef_throwsGuanacoRouteBuilderExceptionAtRouteBuildTime() {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToMerged.class);

        RouteConfig config = routeConfig("direct:orders", Map.of("ToMerged", "mock:merged"));
        GuanacoAggregateConfig agg = new GuanacoAggregateConfig();
        agg.setCorrelationHeader("orderId");
        agg.setStrategyRef("doesNotExist");
        agg.setCompletionSize(1);
        config.setAggregate(agg);

        Processor<RouteOutcome<?>> processor = exchange ->
                new ToMerged(exchange.getIn().getBody(String.class));

        // No strategies registered at all — "doesNotExist" cannot resolve.
        assertThatThrownBy(() ->
                registerRoute(processor, ORDER_ROUTE_CLASS, config, "AggregateTest", registry, Map.of()))
                .isInstanceOf(GuanacoRouteBuilderException.class)
                .hasMessageContaining("doesNotExist");
    }
}