package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

class GuanacoRouteBuilderResequenceTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToOrdered {}
    record ToOrdered(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    @Test
    void batchMode_releasesSortedByCapacity() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToOrdered.class);

        RouteConfig config = routeConfig("direct:orders", Map.of("ToOrdered", "mock:ordered"));
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.BATCH);
        reseq.setCapacity(3);
        config.setResequence(reseq);

        Processor<RouteOutcome<?>> processor = exchange ->
                new ToOrdered(exchange.getIn().getBody(String.class));

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "ResequenceBatchTest", registry, Map.of(), Map.of());
        context.start();

        MockEndpoint ordered = context.getEndpoint("mock:ordered", MockEndpoint.class);
        ordered.expectedMessageCount(3);
        ordered.expectedBodiesReceived("A", "B", "C"); // order matters for this assertion

        ProducerTemplate producer = context.createProducerTemplate();
        // Sent out of order (2, 1, 3) — batch mode must sort before releasing.
        producer.send("direct:orders", e -> { e.getIn().setHeader("seq", 2); e.getIn().setBody("B"); });
        producer.send("direct:orders", e -> { e.getIn().setHeader("seq", 1); e.getIn().setBody("A"); });
        producer.send("direct:orders", e -> { e.getIn().setHeader("seq", 3); e.getIn().setBody("C"); });

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void streamMode_releasesInOrderAsGapsClose() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToOrdered.class);

        RouteConfig config = routeConfig("direct:orders", Map.of("ToOrdered", "mock:ordered"));
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.STREAM);
        reseq.setTimeoutMs(300L); // short, since the test waits on this
        config.setResequence(reseq);

        Processor<RouteOutcome<?>> processor = exchange ->
                new ToOrdered(exchange.getIn().getBody(String.class));

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "ResequenceStreamTest", registry, Map.of(), Map.of());
        context.start();

        MockEndpoint ordered = context.getEndpoint("mock:ordered", MockEndpoint.class);
        ordered.expectedMessageCount(3);
        ordered.expectedBodiesReceived("A", "B", "C");

        ProducerTemplate producer = context.createProducerTemplate();
        producer.send("direct:orders", e -> { e.getIn().setHeader("seq", 2); e.getIn().setBody("B"); });
        producer.send("direct:orders", e -> { e.getIn().setHeader("seq", 1); e.getIn().setBody("A"); });
        producer.send("direct:orders", e -> { e.getIn().setHeader("seq", 3); e.getIn().setBody("C"); });

        // Stream mode's release timing depends on its internal timeout —
        // give assertIsSatisfied a generous wait beyond the configured
        // timeoutMs, since this is inherently timing-sensitive and the
        // most likely of these tests to need tuning on a slow CI runner.
        ordered.setResultWaitTime(3000);
        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void fullPipeline_idempotentThenResequenceThenAggregate() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToOrdered.class);

        RouteConfig config = routeConfig("direct:orders", Map.of("ToOrdered", "mock:merged"));

        var idempotent = new io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig();
        idempotent.setMessageIdHeader("messageId");
        config.setIdempotent(idempotent);

        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.BATCH);
        reseq.setCapacity(3); // exactly 3 distinct genuine messages expected
        config.setResequence(reseq);

        var aggregate = new io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig();
        aggregate.setCorrelationHeader("orderId");
        aggregate.setStrategyRef("concat");
        aggregate.setCompletionSize(3);
        config.setAggregate(aggregate);

        // Order-sensitive: concatenates in arrival order at the aggregate
        // stage. Correct only if resequencing already happened beforehand.
        org.apache.camel.AggregationStrategy concatStrategy = (oldExchange, newExchange) -> {
            if (oldExchange == null) return newExchange;
            String oldBody = oldExchange.getIn().getBody(String.class);
            String newBody = newExchange.getIn().getBody(String.class);
            newExchange.getIn().setBody(oldBody + "," + newBody);
            return newExchange;
        };

        Processor<RouteOutcome<?>> processor = exchange ->
                new ToOrdered(exchange.getIn().getBody(String.class));

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "FullPipelineTest", registry,
                Map.of("concat", concatStrategy), Map.of());
        context.start();

        MockEndpoint merged = context.getEndpoint("mock:merged", MockEndpoint.class);
        merged.expectedMessageCount(1);
        merged.expectedBodiesReceived("A,B,C"); // correctly ordered, duplicate excluded

        ProducerTemplate producer = context.createProducerTemplate();

        // Arrives out of order (seq=2 first), and includes a duplicate
        // (same messageId as the first message) that must be dropped
        // before it ever reaches the resequencer.
        producer.send("direct:orders", e -> {
            e.getIn().setHeader("messageId", "m1");
            e.getIn().setHeader("seq", 2);
            e.getIn().setHeader("orderId", "order-1");
            e.getIn().setBody("B");
        });
        producer.send("direct:orders", e -> {
            e.getIn().setHeader("messageId", "m2");
            e.getIn().setHeader("seq", 1);
            e.getIn().setHeader("orderId", "order-1");
            e.getIn().setBody("A");
        });
        producer.send("direct:orders", e -> {
            e.getIn().setHeader("messageId", "m1"); // duplicate of the first send
            e.getIn().setHeader("seq", 2);
            e.getIn().setHeader("orderId", "order-1");
            e.getIn().setBody("B");
        });
        producer.send("direct:orders", e -> {
            e.getIn().setHeader("messageId", "m3");
            e.getIn().setHeader("seq", 3);
            e.getIn().setHeader("orderId", "order-1");
            e.getIn().setBody("C");
        });

        MockEndpoint.assertIsSatisfied(context);
    }
}