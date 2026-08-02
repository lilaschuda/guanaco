package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Proves that Idempotent Consumer always wraps outermost, wrapping Aggregate
 * — not merely that each feature works in isolation, but that a duplicate is
 * filtered BEFORE it can ever be counted toward an aggregation group's
 * completion. This ordering is fixed and not configurable (see
 * GuanacoIdempotentConfig's class javadoc).
 *
 * Idempotent filters by messageId; Aggregate correlates by orderId — a
 * deliberately different header, matching the real-world shape of this
 * problem: a redelivered duplicate repeats the same message identity, not
 * necessarily anything about which aggregation group it belongs to.
 */
class GuanacoRouteBuilderIdempotentAggregateOrderingTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToMerged {}
    record ToMerged(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    @Test
    void duplicateIsFilteredBeforeCountingTowardAggregateCompletion() throws Exception {
        AggregationStrategy concatStrategy = (oldExchange, newExchange) -> {
            if (oldExchange == null) return newExchange;
            String oldBody = oldExchange.getIn().getBody(String.class);
            String newBody = newExchange.getIn().getBody(String.class);
            newExchange.getIn().setBody(oldBody + "," + newBody);
            return newExchange;
        };

        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToMerged.class);

        RouteConfig config = routeConfig("direct:orders", Map.of("ToMerged", "mock:merged"));

        GuanacoIdempotentConfig idempotent = new GuanacoIdempotentConfig();
        idempotent.setMessageIdHeader("messageId");
        config.setIdempotent(idempotent);

        GuanacoAggregateConfig aggregate = new GuanacoAggregateConfig();
        aggregate.setCorrelationHeader("orderId");
        aggregate.setStrategyRef("concat");
        aggregate.setCompletionSize(2);
        config.setAggregate(aggregate);

        Processor<RouteOutcome<?>> processor = exchange ->
                new ToMerged(exchange.getIn().getBody(String.class));

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "OrderingTest", registry,
                Map.of("concat", concatStrategy));
        context.start();

        MockEndpoint merged = context.getEndpoint("mock:merged", MockEndpoint.class);
        // Exactly one release, from exactly TWO distinct contributions — if
        // the duplicate had wrongly reached the aggregator, this would
        // either complete early with "first,first" or arrive with a
        // different message count than expected.
        merged.expectedMessageCount(1);
        merged.expectedBodiesReceived("first,second");

        ProducerTemplate producer = context.createProducerTemplate();

        producer.send("direct:orders", exchange -> {
            exchange.getIn().setHeader("messageId", "msg-1");
            exchange.getIn().setHeader("orderId", "order-1");
            exchange.getIn().setBody("first");
        });

        // A redelivered duplicate of message 1 — same messageId, would also
        // match the same aggregation group by orderId if it were ever
        // allowed through. Must be absorbed by idempotentConsumer() and
        // never reach the aggregator at all.
        producer.send("direct:orders", exchange -> {
            exchange.getIn().setHeader("messageId", "msg-1");
            exchange.getIn().setHeader("orderId", "order-1");
            exchange.getIn().setBody("first");
        });

        producer.send("direct:orders", exchange -> {
            exchange.getIn().setHeader("messageId", "msg-2");
            exchange.getIn().setHeader("orderId", "order-1");
            exchange.getIn().setBody("second");
        });

        MockEndpoint.assertIsSatisfied(context);
    }
}