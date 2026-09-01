package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.GuanacoConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThreadsConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.context.exception.InvalidRouteConfigurationException;
import io.github.lilaschuda.guanaco.api.Processor;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the route-level Threads pipeline handoff: functional correctness
 * under a configured pool, proof the handoff is genuine (the processor
 * actually runs on a different thread than the caller, not just a no-op
 * config), and the boot-time mutual-exclusion/sizing guardrails.
 */
class GuanacoRouteBuilderThreadsTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToInventory {}
    record ToInventory(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    @Test
    void inlinePool_handsOffToADifferentThread_andDeliversNormally() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));

        GuanacoThreadsConfig threads = new GuanacoThreadsConfig();
        threads.setPoolSize(1);
        threads.setMaxPoolSize(1);
        threads.setThreadName("guanaco-threads-test");
        config.setThreads(threads);

        String callingThreadName = Thread.currentThread().getName();
        AtomicReference<String> processingThreadName = new AtomicReference<>();

        Processor<RouteOutcome<?>> processor = exchange -> {
            processingThreadName.set(Thread.currentThread().getName());
            return new ToInventory(exchange.getIn().getBody(String.class));
        };

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "ThreadsHandoffTest", registry, Map.of(), Map.of());
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedBodiesReceived("hello");

        // sendBody blocks the calling thread until the whole route --
        // including the handed-off portion -- completes, regardless of
        // which internal thread actually did the work.
        context.createProducerTemplate().sendBody("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(context);
        assertThat(processingThreadName.get())
                .as("the processor should run on the configured pool's thread, not the caller's")
                .isNotEqualTo(callingThreadName)
                .contains("guanaco-threads-test");
    }

    @Test
    void threadsConfig_executorServiceRefWithInlineField_failsAtBoot() {
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));
        GuanacoThreadsConfig threads = new GuanacoThreadsConfig();
        threads.setExecutorServiceRef("myPoolBean");
        threads.setPoolSize(5);
        config.setThreads(threads);

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateThreadsConfig("ThreadsBootTest", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("alternative pool sources");
    }

    @Test
    void threadsConfig_emptyConfig_isValid() {
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));
        config.setThreads(new GuanacoThreadsConfig()); // no fields set at all -- Camel's own defaults

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        // should not throw
        validator.validateThreadsConfig("ThreadsBootTest", config);
    }

    @Test
    void threadsConfig_maxPoolSizeSmallerThanPoolSize_failsAtBoot() {
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));
        GuanacoThreadsConfig threads = new GuanacoThreadsConfig();
        threads.setPoolSize(10);
        threads.setMaxPoolSize(5);
        config.setThreads(threads);

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateThreadsConfig("ThreadsBootTest", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("must not be smaller than");
    }
}
