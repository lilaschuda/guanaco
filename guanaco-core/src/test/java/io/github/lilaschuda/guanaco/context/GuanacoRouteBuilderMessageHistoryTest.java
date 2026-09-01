package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.Drop;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener;
import io.github.lilaschuda.guanaco.api.telemetry.RouteSpan;
import io.github.lilaschuda.guanaco.config.GuanacoSampleConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.api.Processor;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the actual behavior message history reporting relies on: the
 * onCompletion() hook firing (and reporting non-empty spans) uniformly
 * across dispatch success, Drop, Sample-rejection, and dead-letter
 * failure — the whole point of using onCompletion() rather than
 * per-hook-point reporting, since a routeStop-based stop (Drop, Sample)
 * would otherwise skip any reporting hook chained after it.
 *
 * <p>Unlike {@link GuanacoRouteBuilderTestSupport}'s other subclasses,
 * this one enables message history directly on the test's own
 * {@code context} in setup, since these tests build routes via
 * {@code registerRoute(...)} directly rather than going through
 * {@code GuanacoContext.wireRoutes()} (which is what normally enables it).
 */
class GuanacoRouteBuilderMessageHistoryTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToInventory {}
    record ToInventory(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    private static class RecordingTelemetryListener implements GuanacoTelemetryListener {
        final List<List<RouteSpan>> reportedHistories = new CopyOnWriteArrayList<>();

        @Override
        public void onMessageHistory(String routeId, List<RouteSpan> history) {
            reportedHistories.add(history);
        }
    }

    @BeforeEach
    void enableMessageHistory() {
        context.setMessageHistory(true);
    }

    @Test
    void successfulDispatch_reportsNonEmptyHistory() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));

        Processor<RouteOutcome<?>> processor = exchange -> new ToInventory(exchange.getIn().getBody(String.class));
        RecordingTelemetryListener listener = new RecordingTelemetryListener();

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "HistorySuccessTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), listener));
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedMessageCount(1);

        context.createProducerTemplate().sendBody("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(context);
        assertThat(listener.reportedHistories).hasSize(1);
        assertThat(listener.reportedHistories.get(0)).isNotEmpty();
    }

    @Test
    void drop_stillReportsHistory() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));

        Processor<RouteOutcome<?>> processor = exchange -> Drop.INSTANCE;
        RecordingTelemetryListener listener = new RecordingTelemetryListener();

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "HistoryDropTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), listener));
        context.start();

        context.createProducerTemplate().sendBody("direct:orders", "hello");

        // No mock assertion possible (nothing gets dispatched) -- history
        // reporting is the only signal that anything happened at all.
        assertThat(listener.reportedHistories).hasSize(1);
        assertThat(listener.reportedHistories.get(0)).isNotEmpty();
    }

    @Test
    void routeLevelSampleRejection_stillReportsHistory() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));
        GuanacoSampleConfig sample = new GuanacoSampleConfig();
        sample.setMessageFrequency(2L); // only the 2nd of every 2 messages passes
        config.setSample(sample);

        Processor<RouteOutcome<?>> processor = exchange -> new ToInventory(exchange.getIn().getBody(String.class));
        RecordingTelemetryListener listener = new RecordingTelemetryListener();

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "HistorySampleRejectTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), listener));
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedMessageCount(1); // only the 2nd message

        // First message is sampled out (rejected, never reaches inventory);
        // second passes. Both should still report history.
        context.createProducerTemplate().sendBody("direct:orders", "1");
        context.createProducerTemplate().sendBody("direct:orders", "2");

        MockEndpoint.assertIsSatisfied(context);
        assertThat(listener.reportedHistories)
                .as("both the rejected and the passed message should report history")
                .hasSize(2);
        assertThat(listener.reportedHistories).allSatisfy(h -> assertThat(h).isNotEmpty());
    }

    @Test
    void dispatchFailure_stillReportsHistory() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);
        RouteConfig config = routeConfigWithDeadLetter(
                "direct:orders", Map.of("ToInventory", "mock:bad"), "mock:dead");

        Processor<RouteOutcome<?>> processor = exchange -> new ToInventory(exchange.getIn().getBody(String.class));
        RecordingTelemetryListener listener = new RecordingTelemetryListener();

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "HistoryFailureTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), listener));
        context.start();

        MockEndpoint bad = context.getEndpoint("mock:bad", MockEndpoint.class);
        bad.whenAnyExchangeReceived(exchange -> {
            throw new IllegalStateException("simulated failure");
        });

        MockEndpoint dead = context.getEndpoint("mock:dead", MockEndpoint.class);
        dead.expectedMessageCount(1);

        context.createProducerTemplate().sendBody("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(dead);
        assertThat(listener.reportedHistories).hasSize(1);
        assertThat(listener.reportedHistories.get(0)).isNotEmpty();
    }
}
