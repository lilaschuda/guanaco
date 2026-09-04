package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.AsyncOutcomeProcessor;
import io.github.lilaschuda.guanaco.api.OutcomeCallback;
import io.github.lilaschuda.guanaco.api.Processor;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.context.exception.GuanacoInspectionException;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for TopologyInspector's recognition of both processor
 * contracts -- Processor and AsyncOutcomeProcessor -- including the
 * boot-time rejection of a class implementing both, which would otherwise
 * resolve silently and unpredictably by JVM reflection ordering.
 *
 * GuanacoContextTest's own topologyInspection_extractsCorrectOutcomes test
 * covers the sync-only case already, against real classpath-scanned
 * fixtures; this file adds the async and dual-contract cases the async
 * processor feature introduced.
 */
class TopologyInspectorAsyncTest {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits OptionA, OptionB {}
    record OptionA(String body) implements OrderRoute<String> {}
    record OptionB(String body) implements OrderRoute<String> {}

    static class SyncStyleProcessor implements Processor<OrderRoute<?>> {
        @Override
        public OrderRoute<?> process(Exchange exchange) {
            return new OptionA("sync");
        }
    }

    static class AsyncStyleProcessor implements AsyncOutcomeProcessor<OrderRoute<?>> {
        @Override
        public void process(Exchange exchange, OutcomeCallback<OrderRoute<?>> callback) {
            callback.onOutcome(new OptionA("async"));
        }
    }

    static class BothContractsProcessor implements Processor<OrderRoute<?>>, AsyncOutcomeProcessor<OrderRoute<?>> {
        @Override
        public OrderRoute<?> process(Exchange exchange) {
            return new OptionA("sync-side");
        }

        @Override
        public void process(Exchange exchange, OutcomeCallback<OrderRoute<?>> callback) {
            callback.onOutcome(new OptionA("async-side"));
        }
    }

    static class NeitherContractProcessor {
        // implements neither Processor nor AsyncOutcomeProcessor
    }

    private final TopologyInspector inspector = new TopologyInspector();

    @Test
    void syncProcessor_extractsCorrectOutcomes() {
        Set<Class<? extends RouteOutcome<?>>> outcomes = inspector.extractRouteOutcomes(SyncStyleProcessor.class);

        assertThat(outcomes)
                .extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("OptionA", "OptionB");
    }

    @Test
    void asyncProcessor_extractsCorrectOutcomes() {
        Set<Class<? extends RouteOutcome<?>>> outcomes = inspector.extractRouteOutcomes(AsyncStyleProcessor.class);

        assertThat(outcomes)
                .extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("OptionA", "OptionB");
    }

    @Test
    void processorImplementingBothContracts_isRejectedAtInspectionTime() {
        assertThatThrownBy(() -> inspector.extractRouteOutcomes(BothContractsProcessor.class))
                .isInstanceOf(GuanacoInspectionException.class)
                .hasMessageContaining("implements both Processor and AsyncOutcomeProcessor")
                .hasMessageContaining("exactly one of the two");
    }

    @Test
    void processorImplementingNeitherContract_isRejectedWithAccurateMessage() {
        assertThatThrownBy(() -> inspector.extractRouteOutcomes(NeitherContractProcessor.class))
                .isInstanceOf(GuanacoInspectionException.class)
                .hasMessageContaining("Processor<R>")
                .hasMessageContaining("AsyncOutcomeProcessor<R>");
    }
}