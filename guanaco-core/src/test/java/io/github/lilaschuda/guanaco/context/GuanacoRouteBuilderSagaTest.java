package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.api.SagaStep;
import io.github.lilaschuda.guanaco.config.GuanacoConfig;
import io.github.lilaschuda.guanaco.config.GuanacoSagaConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.context.exception.GuanacoRouteBuilderException;
import io.github.lilaschuda.guanaco.context.exception.InvalidRouteConfigurationException;
import io.github.lilaschuda.guanaco.api.Processor;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.model.SagaPropagation;
import org.apache.camel.saga.InMemorySagaService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers Saga end to end: successful completion, compensated failure with
 * the snapshotted option surfacing as a real header on the compensation
 * exchange (per Camel's own documented behavior for {@code .option(...)}),
 * the runtime SagaStep-without-saga-config guard, and boot-time validation.
 */
class GuanacoRouteBuilderSagaTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T>
            permits ToInventory, CompensateInventory, CompleteInventory {}
    record ToInventory(String orderId) implements OrderRoute<String> {
        @Override public String body() { return orderId; }
    }
    record CompensateInventory(String orderId) implements OrderRoute<String> {
        @Override public String body() { return orderId; }
    }
    record CompleteInventory(String orderId) implements OrderRoute<String> {
        @Override public String body() { return orderId; }
    }

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    private RouteConfig sagaRouteConfig(String compensationUri, String completionUri) {
        RouteConfig config = routeConfig("direct:orders", Map.of(
                "ToInventory", "mock:inventory",
                "CompensateInventory", compensationUri,
                "CompleteInventory", completionUri));

        GuanacoSagaConfig saga = new GuanacoSagaConfig();
        saga.setCompensation(CompensateInventory.class);
        saga.setCompletion(CompleteInventory.class);
        saga.setOptionKeys(List.of("orderId"));
        config.setSaga(saga);
        return config;
    }

    @Test
    void successfulStep_dispatchesPrimary_andTriggersCompletion() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(
                ToInventory.class, CompensateInventory.class, CompleteInventory.class);
        RouteConfig config = sagaRouteConfig("mock:compensation", "mock:completion");

        Processor<RouteOutcome<?>> processor = exchange -> {
            String orderId = exchange.getIn().getBody(String.class);
            return new SagaStep<>(new ToInventory(orderId), Map.of("orderId", orderId));
        };

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "SagaSuccessTest", registry);
        // These low-level tests bypass GuanacoContext.wireRoutes() (which
        // auto-registers this for real usage), so it's needed explicitly
        // here -- Camel's .saga() has no automatic in-memory fallback of
        // its own (confirmed via SagaReifier.resolveSagaService()).
        context.addService(new InMemorySagaService());
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedBodiesReceived("order-42");

        MockEndpoint completion = context.getEndpoint("mock:completion", MockEndpoint.class);
        completion.expectedMessageCount(1);

        MockEndpoint compensation = context.getEndpoint("mock:compensation", MockEndpoint.class);
        compensation.expectedMessageCount(0);

        context.createProducerTemplate().sendBody("direct:orders", "order-42");

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void failedStep_triggersCompensation_withOptionAsRealHeader() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(
                ToInventory.class, CompensateInventory.class, CompleteInventory.class);
        RouteConfig config = sagaRouteConfig("mock:compensation", "mock:completion");

        Processor<RouteOutcome<?>> processor = exchange -> {
            String orderId = exchange.getIn().getBody(String.class);
            return new SagaStep<>(new ToInventory(orderId), Map.of("orderId", orderId));
        };

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "SagaFailureTest", registry);
        context.addService(new InMemorySagaService());
        context.start();
        
        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.whenAnyExchangeReceived(exchange -> {
            throw new IllegalStateException("simulated inventory failure");
        });

        MockEndpoint compensation = context.getEndpoint("mock:compensation", MockEndpoint.class);
        compensation.expectedMessageCount(1);

        MockEndpoint completion = context.getEndpoint("mock:completion", MockEndpoint.class);
        completion.expectedMessageCount(0);

        // Compensation is a side effect (cleanup), not error handling --
        // Camel's SagaProcessor never clears the original exception after
        // a successful compensate() call (confirmed via source: only a
        // NEW exception from the compensation call itself gets set; the
        // success path never calls exchange.setException(null)). So the
        // original failure still propagates to the caller even though
        // compensation successfully fired.
        assertThatThrownBy(() -> context.createProducerTemplate().sendBody("direct:orders", "order-99"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("simulated inventory failure");

        MockEndpoint.assertIsSatisfied(compensation, completion);
        assertThat(compensation.getExchanges().get(0).getIn().getHeader("orderId"))
                .as("the snapshotted option should surface as a real header, not a body/property the "
                        + "compensation route has to know Guanaco-internal details to read")
                .isEqualTo("order-99");
    }

    @Test
    void sagaStepReturned_withNoSagaConfig_failsAtRuntime() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));
        // deliberately no config.setSaga(...)

        Processor<RouteOutcome<?>> processor = exchange ->
                new SagaStep<>(new ToInventory(exchange.getIn().getBody(String.class)));

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "SagaMismatchTest", registry);
        context.start();

        assertThatThrownBy(() -> context.createProducerTemplate().sendBody("direct:orders", "order-1"))
                .hasRootCauseInstanceOf(GuanacoRouteBuilderException.class)
                .hasRootCauseMessage("[SagaMismatchTest] process() returned a SagaStep, but this route has no "
                        + "'saga' config declared. A SagaStep's options are only ever read by the .saga() "
                        + "block's own boot-time-registered .option(...) expressions -- with no saga config, "
                        + "none exist, so these values would be silently lost. Add a 'saga' block to this "
                        + "route's config.");
    }

    @Test
    void sagaConfig_compensationWithNoBinding_failsAtBoot() {
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));
        GuanacoSagaConfig saga = new GuanacoSagaConfig();
        saga.setCompensation(CompensateInventory.class); // no binding declared for it
        config.setSaga(saga);

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateSagaConfig("SagaBootTest", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("has no binding declared");
    }

    @Test
    void sagaConfig_duplicateOptionKeys_failsAtBoot() {
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));
        GuanacoSagaConfig saga = new GuanacoSagaConfig();
        saga.setOptionKeys(List.of("orderId", "orderId"));
        config.setSaga(saga);

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateSagaConfig("SagaBootTest", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("duplicate key");
    }

    @Test
    void sagaConfig_validConfig_passesBootValidation() {
        RouteConfig config = sagaRouteConfig("mock:compensation", "mock:completion");
        config.getSaga().setPropagation(SagaPropagation.REQUIRED);
        config.getSaga().setTimeoutMs(5000L);

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        // should not throw
        validator.validateSagaConfig("SagaBootTest", config);
    }
}
