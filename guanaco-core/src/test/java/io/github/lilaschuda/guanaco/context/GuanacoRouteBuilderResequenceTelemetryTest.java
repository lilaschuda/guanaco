package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.api.Processor;
import io.github.lilaschuda.guanaco.api.telemetry.FailureRecord;
import io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the previously-missing rejected=false side of resequence telemetry
 * (added alongside the rejected=true route-level {@code onException}
 * handling that already existed) actually fires, once per message that
 * makes it through resequencing.
 */
class GuanacoRouteBuilderResequenceTelemetryTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToOrdered {}
    record ToOrdered(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    private static class RecordingTelemetryListener implements GuanacoTelemetryListener {
        final List<Boolean> resequenceEvents = new CopyOnWriteArrayList<>();

        @Override
        public void onResequenceEvent(String routeId, boolean rejected) {
            resequenceEvents.add(rejected);
        }

    }

    @Test
    void batchMode_reportsProcessedForEveryMessage() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToOrdered.class);

        RouteConfig config = routeConfig("direct:orders", Map.of("ToOrdered", "mock:ordered"));
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.BATCH);
        reseq.setCapacity(3);
        config.setResequence(reseq);

        Processor<RouteOutcome<?>> processor = exchange -> new ToOrdered(exchange.getIn().getBody(String.class));
        RecordingTelemetryListener listener = new RecordingTelemetryListener();

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "ResequenceTelemetryTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), listener));
        context.start();

        MockEndpoint ordered = context.getEndpoint("mock:ordered", MockEndpoint.class);
        ordered.expectedMessageCount(3);

        ProducerTemplate producer = context.createProducerTemplate();
        producer.send("direct:orders", e -> { e.getIn().setHeader("seq", 2); e.getIn().setBody("B"); });
        producer.send("direct:orders", e -> { e.getIn().setHeader("seq", 1); e.getIn().setBody("A"); });
        producer.send("direct:orders", e -> { e.getIn().setHeader("seq", 3); e.getIn().setBody("C"); });

        MockEndpoint.assertIsSatisfied(ordered);
        assertThat(listener.resequenceEvents).hasSize(3).allMatch(rejected -> !rejected);
    }
}