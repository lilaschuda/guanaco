package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.api.telemetry.FailureRecord;
import io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener;
import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.api.Processor;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The circuit-breaker counterpart to {@link GuanacoRouteBuilderTelemetryFailureTest}.
 * Proves {@code GuanacoResilienceHelper.applyCircuitBreaker}'s {@code doFinally}
 * block reports the real exception to {@code onOutcomeFailed} -- not a
 * {@code cause.getCause()}-unwrapped {@code null} (a real bug found during
 * review: {@code RuntimeExchangeException.wrapRuntimeException} only wraps
 * checked exceptions -- "don't double wrap" -- so a plain downstream
 * {@code RuntimeException}, the common case, reaches this handler unwrapped
 * with no further cause to unwrap).
 */
class GuanacoRouteBuilderCircuitBreakerTelemetryTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToMerged {}
    record ToMerged(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    private static class RecordingTelemetryListener implements GuanacoTelemetryListener {
        final List<FailureRecord> failures = new CopyOnWriteArrayList<>();

        @Override
        public void onOutcomeFailed(String routeId, String targetUri, Throwable cause) {
            failures.add(new FailureRecord(
                    Instant.now(), routeId, targetUri,
                    cause == null ? "null" : cause.getClass().getSimpleName(),
                    cause == null ? null : cause.getMessage()));
        }
    }

    @Test
    void circuitBreakerDispatchFailure_reportsRealException_notNull() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToMerged.class);

        RouteConfig config = routeConfigWithDeadLetter("direct:orders", Map.of("ToMerged", "mock:bad"), "mock:dead");
        GuanacoCircuitBreakerConfig cb = new GuanacoCircuitBreakerConfig();
        cb.setMinimumNumberOfCalls(1);
        config.setCircuitBreaker(cb);

        Processor<RouteOutcome<?>> processor = exchange -> new ToMerged(exchange.getIn().getBody(String.class));
        RecordingTelemetryListener listener = new RecordingTelemetryListener();

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "CircuitBreakerTelemetryTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), listener));
        context.start();

        MockEndpoint bad = context.getEndpoint("mock:bad", MockEndpoint.class);
        // A plain RuntimeException -- the common case, and specifically the
        // case wrapRuntimeException does NOT wrap ("don't double wrap"), so
        // exchange.getException() reaches our doFinally block unwrapped.
        bad.whenAnyExchangeReceived(exchange -> {
            throw new IllegalStateException("simulated downstream failure");
        });

        MockEndpoint dead = context.getEndpoint("mock:dead", MockEndpoint.class);
        dead.expectedMessageCount(1);

        context.createProducerTemplate().sendBody("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(dead);
        assertThat(listener.failures)
                .hasSize(1)
                .first()
                .satisfies(record -> {
                    assertThat(record.targetUri()).isEqualTo("mock:bad");
                    assertThat(record.processorName()).isEqualTo("CircuitBreakerTelemetryTest");
                    // Before the fix: this was "null" (cause.getCause() on an
                    // unwrapped RuntimeException with no cause of its own).
                    assertThat(record.exceptionType()).isEqualTo("IllegalStateException");
                    assertThat(record.exceptionMessage()).isEqualTo("simulated downstream failure");
                });
    }
}