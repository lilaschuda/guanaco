package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.context.RouteOutcomeRegistry;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.api.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

class GuanacoRouteBuilderIdempotentTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToInventory {}
    record ToInventory(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    @Test
    void duplicateMessageId_isFilteredBeforeReachingTheProcessor() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);

        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));
        GuanacoIdempotentConfig idempotent = new GuanacoIdempotentConfig();
        idempotent.setMessageIdHeader("orderId");
        config.setIdempotent(idempotent);

        Processor<RouteOutcome<?>> processor = exchange ->
                new ToInventory(exchange.getIn().getBody(String.class));

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "IdempotentTest", registry, Map.of(), Map.of());
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        // Only ONE message reaches the endpoint, despite sending the same
        // orderId twice — proving the duplicate was absorbed by
        // idempotentConsumer() and never reached dispatchOutcome/choice() at all.
        inventory.expectedMessageCount(1);

        ProducerTemplate producer = context.createProducerTemplate();
        producer.send("direct:orders", exchange -> {
            exchange.getIn().setHeader("orderId", "order-1");
            exchange.getIn().setBody("first-delivery");
        });
        producer.send("direct:orders", exchange -> {
            exchange.getIn().setHeader("orderId", "order-1"); // same ID — duplicate
            exchange.getIn().setBody("redelivered-attempt");
        });

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void distinctMessageIds_bothReachTheProcessor() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);

        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));
        GuanacoIdempotentConfig idempotent = new GuanacoIdempotentConfig();
        idempotent.setMessageIdHeader("orderId");
        config.setIdempotent(idempotent);

        Processor<RouteOutcome<?>> processor = exchange ->
                new ToInventory(exchange.getIn().getBody(String.class));

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "IdempotentTest", registry, Map.of(), Map.of());
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedMessageCount(2);

        ProducerTemplate producer = context.createProducerTemplate();
        producer.send("direct:orders", exchange -> {
            exchange.getIn().setHeader("orderId", "order-1");
            exchange.getIn().setBody("first");
        });
        producer.send("direct:orders", exchange -> {
            exchange.getIn().setHeader("orderId", "order-2");
            exchange.getIn().setBody("second");
        });

        MockEndpoint.assertIsSatisfied(context);
    }
}