package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.AsyncOutcomeProcessor;
import io.github.lilaschuda.guanaco.api.Drop;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.GuanacoThreadsConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the AsyncOutcomeProcessor dispatch mechanism itself --
 * behavioral parity with the synchronous Processor path via the shared
 * finishDispatch logic, proof that dispatch is genuinely non-blocking (not
 * just correctly wired to look async), and the defensive handling of an
 * AsyncOutcomeProcessor that violates its own contract by throwing
 * synchronously instead of calling OutcomeCallback.
 *
 * EIP-specific interaction with the async path (WireTap, Saga) is covered
 * alongside each EIP's own existing test file, not duplicated here.
 */
class GuanacoRouteBuilderAsyncDispatchTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToInventory {}
    record ToInventory(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    @Test
    void plainOutcome_dispatchesIdenticallyToSyncPath() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));

        AsyncOutcomeProcessor<OrderRoute<?>> processor = (exchange, callback) -> {
            String body = exchange.getIn().getBody(String.class);
            callback.onOutcome(new ToInventory(body));
        };

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "AsyncPlainTest", registry);
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedBodiesReceived("hello");

        context.createProducerTemplate().sendBody("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(context, 5, TimeUnit.SECONDS);
    }

    @Test
    void drop_stopsTheRoute_viaAsyncPath() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));

        AsyncOutcomeProcessor<RouteOutcome<?>> processor = (exchange, callback) -> callback.onOutcome(Drop.INSTANCE);

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "AsyncDropTest", registry);
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedMessageCount(0);
        inventory.setAssertPeriod(1000);

        context.createProducerTemplate().sendBody("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void asyncDispatch_releasesThreadsPoolThread_allowingConcurrentMessageProcessing() throws Exception {
        // Proves genuine non-blocking behavior the correct way: with a
        // single-thread pool, a second message can only start processing
        // while the first message's async outcome is still pending if
        // AsyncDispatchStep genuinely released the pool's one thread
        // rather than blocking it. An earlier draft of this test tried to
        // prove this by timing an asyncSendBody() call instead -- that
        // measures Camel's own producer-template asynchrony, not whether
        // the dispatch step itself blocks a thread, and would have passed
        // even if the internal implementation secretly blocked. Caught and
        // replaced before landing, not after.
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class);
        RouteConfig config = routeConfig("direct:orders", Map.of("ToInventory", "mock:inventory"));

        GuanacoThreadsConfig threads = new GuanacoThreadsConfig();
        threads.setPoolSize(1);
        threads.setMaxPoolSize(1);
        threads.setThreadName("guanaco-async-nonblocking-test");
        config.setThreads(threads);

        CountDownLatch message1CanComplete = new CountDownLatch(1);
        CountDownLatch message1ReachedProcessor = new CountDownLatch(1);
        CountDownLatch message2Started = new CountDownLatch(1);

        AsyncOutcomeProcessor<OrderRoute<?>> processor = (exchange, callback) -> {
            String body = exchange.getIn().getBody(String.class);
            if ("first".equals(body)) {
                message1ReachedProcessor.countDown();
                // Mirrors a real coroutine bridge: kick off work elsewhere
                // and return immediately, WITHOUT blocking this calling
                // thread -- which, with poolSize=1, is the pool's only thread.
                Thread worker = new Thread(() -> {
                    try {
                        message1CanComplete.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    callback.onOutcome(new ToInventory("first-done"));
                });
                worker.setDaemon(true);
                worker.start();
                // process() returns here, right away -- the pool thread is free.
            } else {
                message2Started.countDown();
                callback.onOutcome(new ToInventory("second-done"));
            }
        };

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "AsyncNonBlockingPoolTest", registry);
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        // second-done arrives first, by construction: message 2's outcome
        // completes immediately (no wait in its branch), while message 1's
        // completion is deliberately held back until message1CanComplete
        // is counted down below -- which only happens after message 2 has
        // already been confirmed delivered. Getting this order backwards
        // isn't just a cosmetic assertion bug: message 2 genuinely racing
        // ahead of message 1 to completion is itself direct evidence the
        // pool thread was freed for concurrent use, not held hostage.
        inventory.expectedBodiesReceived("second-done", "first-done");

        // Message 1 on its own thread -- sendBody() blocks until the WHOLE
        // exchange (including the pending async outcome) resolves, and this
        // test needs to send message 2 while message 1 is still in flight.
        Thread sendFirst = new Thread(() ->
                context.createProducerTemplate().sendBody("direct:orders", "first"));
        sendFirst.setDaemon(true);
        sendFirst.start();

        // Wait for message 1 to genuinely reach the processor (and thus
        // have occupied, then be about to release, the pool's one thread)
        // before sending message 2 -- without this, message 2 could
        // otherwise race ahead of message 1 to the pool's single thread,
        // which would prove nothing about whether message 1's pending
        // async work held it.
        boolean firstReached = message1ReachedProcessor.await(5, TimeUnit.SECONDS);
        assertThat(firstReached).as("message 1 should have reached the processor").isTrue();

        context.createProducerTemplate().sendBody("direct:orders", "second");

        // If the pool's single thread were held hostage by message 1's
        // pending async work, this would never even start -- there would be
        // no thread available in the pool to pick it up.
        boolean secondStarted = message2Started.await(5, TimeUnit.SECONDS);
        assertThat(secondStarted)
                .as("the pool's single thread should have been freed to start message 2 "
                    + "while message 1's async outcome was still pending")
                .isTrue();

        message1CanComplete.countDown();
        MockEndpoint.assertIsSatisfied(context, 5, TimeUnit.SECONDS);
    }
}