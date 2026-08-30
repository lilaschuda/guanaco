package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.context.RouteOutcomeRegistry;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.api.Processor;
import io.github.lilaschuda.guanaco.api.Drop;
import io.github.lilaschuda.guanaco.api.Multicast;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Drop and Multicast are framework-recognized outcomes, handled before the
 * choice() dispatch table runs — so these tests construct the builder
 * directly via GuanacoRouteBuilderTestSupport rather than going through
 * GuanacoContext.wireRoutes(), since TopologyInspector/BindingValidator are
 * not involved in this path.
 */
class GuanacoRouteBuilderDropMulticastTest extends GuanacoRouteBuilderTestSupport {

    record ToInventory(String body)  implements RouteOutcome<String> {}
    record ToPayment(String body)    implements RouteOutcome<String> {}
    record ToFraudCheck(String body) implements RouteOutcome<String> {}

    @Test
    void drop_discardsMessage_noEndpointReceivesIt() throws Exception {
        // Drop short-circuits in dispatchOutcome before any registry check —
        // an empty registry is sufficient here.
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of();

        RouteConfig config = routeConfig("direct:dropTest", Map.of());

        Processor<RouteOutcome<?>> processor = message -> Drop.INSTANCE;

        registerRoute(processor, config, "dropTest", registry);
        context.start();

        MockEndpoint shouldNeverBeCalled = context.getEndpoint("mock:shouldNeverBeCalled", MockEndpoint.class);
        shouldNeverBeCalled.expectedMessageCount(0);

        ProducerTemplate producer = context.createProducerTemplate();
        producer.sendBody("direct:dropTest", "irrelevant payload");

        shouldNeverBeCalled.assertIsSatisfied();
    }

    @Test
    void multicast_fansOutToEachDestination() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(
                ToInventory.class, ToPayment.class, ToFraudCheck.class);

        RouteConfig config = routeConfig("direct:multicastTest", Map.of(
                "ToInventory",  "mock:inventory",
                "ToFraudCheck", "mock:fraud"
        ));

        Processor<RouteOutcome<?>> processor = message -> new Multicast(List.of(
                new ToInventory("to-inventory"),
                new ToFraudCheck("to-fraud")
        ));

        registerRoute(processor, config, "multicastTest", registry);
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        MockEndpoint fraud     = context.getEndpoint("mock:fraud",     MockEndpoint.class);
        inventory.expectedMessageCount(1);
        fraud.expectedMessageCount(1);
        inventory.expectedBodiesReceived("to-inventory");
        fraud.expectedBodiesReceived("to-fraud");

        ProducerTemplate producer = context.createProducerTemplate();
        producer.sendBody("direct:multicastTest", "trigger");

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void multicastDestinationNotInRegistry_isRejectedAndLoggedWithoutCrashingTheRoute() throws Exception {
        // ToFraudCheck deliberately excluded from the registry, even though
        // it has a valid YAML binding — modeling a runtime-constructed
        // instance whose class was never part of the boot-time scan.
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);

        RouteConfig config = routeConfig("direct:multicastTest", Map.of(
                "ToInventory",  "mock:inventory",
                "ToFraudCheck", "mock:fraud"
        ));

        Processor<RouteOutcome<?>> processor = message -> new Multicast(List.of(
                new ToInventory("to-inventory"),
                new ToFraudCheck("should-be-rejected")
        ));

        registerRoute(processor, config, "multicastTest", registry);
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        MockEndpoint fraud     = context.getEndpoint("mock:fraud",     MockEndpoint.class);
        inventory.expectedMessageCount(1);
        fraud.expectedMessageCount(0); // rejected before dispatch, despite having a valid binding

        ProducerTemplate producer = context.createProducerTemplate();
        producer.sendBody("direct:multicastTest", "trigger");

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void multicastDestinationWithNoBinding_isSkippedWithoutCrashingTheRoute() throws Exception {
        // ToFraudCheck is registered (passes the defense-in-depth check) but
        // deliberately left unbound in routes.yaml.
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(
                ToInventory.class, ToFraudCheck.class);

        RouteConfig config = routeConfig("direct:multicastTest", Map.of(
                "ToInventory", "mock:inventory"
        ));

        Processor<RouteOutcome<?>> processor = message -> new Multicast(List.of(
                new ToInventory("to-inventory"),
                new ToFraudCheck("orphaned")
        ));

        registerRoute(processor, config, "multicastTest", registry);
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedMessageCount(1);

        ProducerTemplate producer = context.createProducerTemplate();
        producer.sendBody("direct:multicastTest", "trigger");

        MockEndpoint.assertIsSatisfied(context);
    }
}