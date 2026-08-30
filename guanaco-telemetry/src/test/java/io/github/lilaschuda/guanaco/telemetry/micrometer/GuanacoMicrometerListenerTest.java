package io.github.lilaschuda.guanaco.telemetry.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GuanacoMicrometerListenerTest {

    private SimpleMeterRegistry registry;
    private GuanacoMicrometerListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new GuanacoMicrometerListener(registry);
    }

    @Test
    void recordsOutcomeDispatchTimerWithCorrectTags() {
        listener.onOutcomeDispatched("order-route", "OrderCreated", "direct:shipping", 120);

        Timer timer = registry.find("guanaco.outcome.dispatch")
                .tag("route", "order-route")
                .tag("outcome", "OrderCreated")
                .tag("target", "direct:shipping")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120.0);
    }

    @Test
    void recordsOutcomeFailureCounterWithExceptionTag() {
        listener.onOutcomeFailed("order-route", "direct:shipping", new IllegalStateException("Timeout"));

        Counter counter = registry.find("guanaco.outcome.failures")
                .tag("route", "order-route")
                .tag("target", "direct:shipping")
                .tag("exception", "IllegalStateException")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}