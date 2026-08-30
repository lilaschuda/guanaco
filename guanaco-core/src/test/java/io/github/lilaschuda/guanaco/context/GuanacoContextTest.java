package io.github.lilaschuda.guanaco.context;

import java.util.Set;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import io.github.lilaschuda.guanaco.context.GuanacoContext;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.example.OrderRoute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

/**
 * End-to-end integration test for camel-guanaco v0.1.
 * Uses Camel's mock: component to verify routing outcomes.
 */
class GuanacoContextTest {

    private static final Logger log = LoggerFactory.getLogger(GuanacoContextTest.class);
    
    private GuanacoContext guanacoContext;

    @BeforeEach
    void setUp() throws Exception {
        log.info("Setting up test...");
        guanacoContext = new GuanacoContext("io.github.lilaschuda.guanaco.example");
        ApplicationContext ctx = new StaticApplicationContext();
        guanacoContext.setApplicationContext(ctx);
        guanacoContext.wireRoutes();
        guanacoContext.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        guanacoContext.stop();
    }

    @Test
    void normalOrder_routesToInventory() throws Exception {
        log.info("normalOrder_routesToInventory test...");
        MockEndpoint inventory = guanacoContext.getEndpoint("mock:inventory", MockEndpoint.class);
        MockEndpoint payment   = guanacoContext.getEndpoint("mock:payment",   MockEndpoint.class);
        MockEndpoint fraud     = guanacoContext.getEndpoint("mock:fraud",      MockEndpoint.class);

        inventory.expectedMessageCount(1);
        payment.expectedMessageCount(0);
        fraud.expectedMessageCount(0);

        ProducerTemplate producer = guanacoContext.createProducerTemplate();
        producer.sendBody("direct:orders", "order-123");

        MockEndpoint.assertIsSatisfied(guanacoContext);
    }

    @Test
    void unpaidOrder_routesToPayment() throws Exception {
        log.info("unpaidOrder_routesToPayment test...");
        MockEndpoint inventory = guanacoContext.getEndpoint("mock:inventory", MockEndpoint.class);
        MockEndpoint payment   = guanacoContext.getEndpoint("mock:payment",   MockEndpoint.class);
        MockEndpoint fraud     = guanacoContext.getEndpoint("mock:fraud",      MockEndpoint.class);

        inventory.expectedMessageCount(0);
        payment.expectedMessageCount(1);
        fraud.expectedMessageCount(0);

        ProducerTemplate producer = guanacoContext.createProducerTemplate();
        producer.sendBody("direct:orders", "unpaid-order-456");

        MockEndpoint.assertIsSatisfied(guanacoContext);
    }

    @Test
    void suspiciousOrder_routesToFraud() throws Exception {
        log.info("suspiciousOrder_routesToFraud test...");
        MockEndpoint inventory = guanacoContext.getEndpoint("mock:inventory", MockEndpoint.class);
        MockEndpoint payment   = guanacoContext.getEndpoint("mock:payment",   MockEndpoint.class);
        MockEndpoint fraud = guanacoContext.getEndpoint("mock:fraud", MockEndpoint.class);

        inventory.expectedMessageCount(0);
        payment.expectedMessageCount(0);
        fraud.expectedMessageCount(1);

        ProducerTemplate producer = guanacoContext.createProducerTemplate();
        producer.sendBody("direct:orders", "suspicious-order-789");

        MockEndpoint.assertIsSatisfied(guanacoContext);
    }

    @Test
    void topologyInspection_extractsCorrectOutcomes() {
        log.info("Inspecting topology test...");
        var inspector = new io.github.lilaschuda.guanaco.context.TopologyInspector();

        Set<Class<? extends RouteOutcome<?>>> outcomes
                = inspector.extractRouteOutcomes(io.github.lilaschuda.guanaco.example.OrderProcessor.class);

        assertThat(outcomes)
                .allSatisfy(outcomeClass -> {
                    assertThat(OrderRoute.class.isAssignableFrom(outcomeClass)).isTrue();
                    assertThat(outcomeClass.getSimpleName())
                            .isIn("ToInventory", "ToPayment", "ToFraudCheck");
                });
    }
}
