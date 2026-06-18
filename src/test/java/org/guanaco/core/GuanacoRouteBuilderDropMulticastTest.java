package org.guanaco.core;

import io.github.lilaschuda.guanaco.core.RouteOutcome;
import io.github.lilaschuda.guanaco.core.GuanacoRouteBuilder;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import io.github.lilaschuda.guanaco.eip.Drop;
import io.github.lilaschuda.guanaco.eip.Multicast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class GuanacoRouteBuilderDropMulticastTest {

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ROUTE_OUTCOME_CLASS =
        (Class<? extends RouteOutcome<?>>) (Class<?>) RouteOutcome.class;

    private CamelContext context;

    @BeforeEach
    void setUp() {
        context = new DefaultCamelContext();
    }

    @AfterEach
    void tearDown() {
        context.stop();
    }

    @Test
    void drop_discardsMessage_noEndpointReceivesIt() throws Exception {
        RouteConfig config = new RouteConfig();
        config.setFrom("direct:dropTest");
        config.setBindings(Map.of());

        Processor<RouteOutcome<?>> processor = message -> Drop.INSTANCE;

        context.addRoutes(new GuanacoRouteBuilder(processor, ROUTE_OUTCOME_CLASS, config, "dropTest"));
        context.start();

        MockEndpoint shouldNeverBeCalled = context.getEndpoint("mock:shouldNeverBeCalled", MockEndpoint.class);
        shouldNeverBeCalled.expectedMessageCount(0);

        ProducerTemplate producer = context.createProducerTemplate();
        producer.sendBody("direct:dropTest", "irrelevant payload");

        shouldNeverBeCalled.assertIsSatisfied();
    }

    @Test
    void multicast_fansOutToEachDestination() throws Exception {
        RouteConfig config = new RouteConfig();
        config.setFrom("direct:multicastTest");
        config.setBindings(Map.of(
            "FirstDestination",  "mock:first",
            "SecondDestination", "mock:second"
        ));

        Processor<RouteOutcome<?>> processor = message -> new Multicast(List.of(
            new FirstDestination("to-first"),
            new SecondDestination("to-second")
        ));

        context.addRoutes(new GuanacoRouteBuilder(processor, ROUTE_OUTCOME_CLASS, config, "multicastTest"));
        context.start();

        MockEndpoint first  = context.getEndpoint("mock:first",  MockEndpoint.class);
        MockEndpoint second = context.getEndpoint("mock:second", MockEndpoint.class);
        first.expectedMessageCount(1);
        second.expectedMessageCount(1);
        first.expectedBodiesReceived("to-first");
        second.expectedBodiesReceived("to-second");

        ProducerTemplate producer = context.createProducerTemplate();
        producer.sendBody("direct:multicastTest", "trigger");

        MockEndpoint.assertIsSatisfied(context);
    }

    record FirstDestination(String body)  implements RouteOutcome<String> {}
    record SecondDestination(String body) implements RouteOutcome<String> {}
}