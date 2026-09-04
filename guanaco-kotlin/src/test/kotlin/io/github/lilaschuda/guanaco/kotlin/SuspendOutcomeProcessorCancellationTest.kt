package io.github.lilaschuda.guanaco.kotlin

import io.github.lilaschuda.guanaco.api.GuanacoRoute
import io.github.lilaschuda.guanaco.api.RouteOutcome
import io.github.lilaschuda.guanaco.config.BindingTarget
import io.github.lilaschuda.guanaco.testutils.GuanacoTestSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.apache.camel.Exchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Confirms that stopping a GuanacoContext cancels an in-flight suspend
 * function -- exercising the real kotlinx-coroutines-core library, same
 * caveat as SuspendOutcomeProcessorRealCoroutinesTest: this couldn't be run
 * against the genuine library while designing this feature, so this is
 * what actually closes that verification gap.
 *
 * Also exercises the CancellationException special-case in
 * SuspendOutcomeProcessor.process() end to end: onFailure(...) reporting
 * the cancellation to Camel's inflight tracking, and the rethrow
 * afterward, together, against the real library's actual scheduling --
 * not just the stub-based check done while designing the fix, which could
 * only confirm the catch-then-report ordering, not the real asynchronous
 * launch{} behavior around the rethrow.
 */
class SuspendOutcomeProcessorCancellationTest {

    sealed interface OrderRoute : RouteOutcome<Void>
    data class SlowResultInventory(val value: String) : OrderRoute {
        override fun body(): Void? = null
    }

    companion object {
        val startedProcessing = CountDownLatch(1)
        val cancellationObserved = AtomicBoolean(false)
    }

    @GuanacoRoute
    class SlowProcessor : SuspendOutcomeProcessor<OrderRoute>() {
        override suspend fun processSuspending(exchange: Exchange): OrderRoute {
            startedProcessing.countDown()
            try {
                // Long enough that the test's own shutdown() below should
                // cancel this well before it would naturally elapse.
                delay(30_000)
            } catch (e: CancellationException) {
                cancellationObserved.set(true)
                throw e // required coroutine hygiene -- see SuspendOutcomeProcessor
            }
            return SlowResultInventory("should never reach here")
        }
    }

    @Test
    fun `stopping the context cancels an in-flight suspend function`() {
        val support = GuanacoTestSupport("io.github.lilaschuda.guanaco.kotlin")
            .route(
                "SlowProcessor",
                "direct:orders",
                mapOf("SlowResultInventory" to listOf(BindingTarget().apply { uri = "mock:inventory" }))
            )

        val env = support.start()

        // Camel's default ShutdownStrategy timeout is 45 seconds, and its
        // doStop() sequence attempts to gracefully drain inflight exchanges
        // (including this test's deliberately-stuck one) BEFORE stopping
        // registered services -- AbstractCamelContext.doStop() stops them
        // "as late as possible", after shutdownForced() has run. Without
        // shortening this, GuanacoCoroutineScopeService.stop() (and thus
        // job.cancel()) might not fire until close to that 45s window,
        // which is why cancellation could still appear unobserved within a
        // much shorter poll deadline below.
        env.applicationContext.shutdownStrategy.timeout = 2
        env.applicationContext.shutdownStrategy.timeUnit = TimeUnit.SECONDS

        // send(...) blocks until the exchange resolves, which here means
        // "until cancelled" -- run on a background thread so this test can
        // proceed to call shutdown() while the request is still in flight.
        val sender = Thread { env.send("direct:orders", "hello") }
        sender.isDaemon = true
        sender.start()

        assertThat(startedProcessing.await(5, TimeUnit.SECONDS))
            .`as`("the suspend function should have started before we attempt to cancel it")
            .isTrue()

        // Cancels GuanacoCoroutineScopeService's Job, per its own stop() implementation.
        env.shutdown()

        val deadline = System.currentTimeMillis() + 5000
        while (!cancellationObserved.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertThat(cancellationObserved.get())
            .`as`("the in-flight suspend function should have observed cancellation when the context stopped")
            .isTrue()
    }
}