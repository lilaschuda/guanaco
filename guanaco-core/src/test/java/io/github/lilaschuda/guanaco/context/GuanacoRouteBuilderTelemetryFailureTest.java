package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.api.Processor;
import io.github.lilaschuda.guanaco.api.telemetry.FailureRecord;
import io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener;
import java.time.Instant;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real bug found during review: {@code attachPlainTo}'s
 * telemetry-enabled path used {@code doCatch(Throwable.class)} to record
 * {@code onOutcomeFailed}, but never rethrew afterward. Unlike top-level
 * {@code onException()}, Camel's {@code doCatch} has no {@code .handled(...)}
 * opt-out -- it unconditionally marks the exchange's exception handled and
 * clears it (see {@code org.apache.camel.processor.CatchProcessor}). Without
 * a rethrow, a dispatch failure was recorded correctly by telemetry but then
 * silently treated as delivered: it never reached the dead-letter channel
 * and was never redelivered. This test proves the fix (rethrow after
 * recording, mirroring what {@code GuanacoResilienceHelper} already did for
 * the circuit-breaker path) restores the pre-telemetry behavior exactly.
 */
class GuanacoRouteBuilderTelemetryFailureTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToMerged {}
    record ToMerged(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    private static class RecordingTelemetryListener implements GuanacoTelemetryListener {
        final List<FailureRecord> failures = new CopyOnWriteArrayList<>();

        @Override
        public void onOutcomeFailed(String routeId, String targetUri, Throwable cause) {
            FailureRecord failure = new FailureRecord(Instant.now(), "Processor", "mock:bad", "failure", "Error");
            failures.add(failure);
        }

        @Override
        public List<FailureRecord> recentFailures() {
            return this.recentFailures();
        }
    }

    @Test
    void dispatchFailure_withTelemetryEnabled_stillReachesDeadLetter_andReportsFailure() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToMerged.class);
        RouteConfig config = routeConfigWithDeadLetter(
                "direct:orders", Map.of("ToMerged", "mock:bad"), "mock:dead");

        Processor<RouteOutcome<?>> processor = exchange -> new ToMerged(exchange.getIn().getBody(String.class));
        RecordingTelemetryListener listener = new RecordingTelemetryListener();

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "DispatchFailureTelemetryTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), listener));
        context.start();

        MockEndpoint bad = context.getEndpoint("mock:bad", MockEndpoint.class);
        bad.whenAnyExchangeReceived(exchange -> {
            throw new IllegalStateException("simulated downstream failure");
        });

        MockEndpoint dead = context.getEndpoint("mock:dead", MockEndpoint.class);
        dead.expectedMessageCount(1);

        context.createProducerTemplate().sendBody("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(dead);
        FailureRecord failure = new FailureRecord(Instant.now(), "Processor", "mock:bad", "failure", "Error");
        assertThat(listener.failures)
        .hasSize(1)
        .first()
        .satisfies(record -> {
            assertThat(record.targetUri()).isEqualTo("mock:bad");
            assertThat(record.processorName()).isEqualTo("Processor");
            assertThat(record.exceptionType()).isEqualTo("failure");
            assertThat(record.exceptionMessage()).isEqualTo("Error");
            assertThat(record.timestamp()).isNotNull();
        });
    }
}