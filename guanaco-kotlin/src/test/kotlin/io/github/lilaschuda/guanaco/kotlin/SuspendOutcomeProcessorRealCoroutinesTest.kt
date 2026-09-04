package io.github.lilaschuda.guanaco.kotlin

import io.github.lilaschuda.guanaco.api.GuanacoRoute
import io.github.lilaschuda.guanaco.api.RouteOutcome
import io.github.lilaschuda.guanaco.config.BindingTarget
import io.github.lilaschuda.guanaco.testutils.GuanacoTestSupport
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.apache.camel.Exchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Exercises SuspendOutcomeProcessor against the REAL kotlinx-coroutines-core
 * library -- actual `delay()`, actual dispatcher hops -- through a real,
 * embedded GuanacoContext via GuanacoTestSupport, the same public test
 * infrastructure a consumer would use.
 *
 * Everything else verified during this feature's design used either real
 * JVM concurrency directly or a minimal Kotlin-stdlib-only stand-in for
 * kotlinx-coroutines-core (network access to the real library wasn't
 * available while designing this). This is the test that actually closes
 * that gap -- if it passes, the bridge works against the genuine library,
 * not just an approximation of its API shape.
 */
class SuspendOutcomeProcessorRealCoroutinesTest {

    sealed interface OrderRoute : RouteOutcome<String>
    data class DelayResultInventory(val value: String) : OrderRoute {
        override fun body(): String = value
    }

    // @GuanacoRoute is required for GuanacoContext's classpath scan to find
    // this class at all -- omitting it wouldn't fail loudly, wireRoutes()
    // would just log a warning and silently skip it, which is exactly the
    // kind of gap that's easy to not notice in a test that otherwise looks
    // like it should work.
    @GuanacoRoute
    class DelayingProcessor : SuspendOutcomeProcessor<OrderRoute>() {
        override suspend fun processSuspending(exchange: Exchange): OrderRoute {
            // A real suspension point via withContext + delay, not a no-op --
            // proves this genuinely suspends and correctly resumes against
            // the real library, including an actual dispatcher hop. Not
            // asserting the thread changes across the hop: Dispatchers.IO
            // and Dispatchers.Default share the same underlying worker pool
            // in the real library, so that isn't a deterministic guarantee
            // and would make this test flaky rather than reliable.
            val computed = withContext(Dispatchers.IO) {
                delay(50)
                "resumed-after-delay"
            }
            val body = exchange.getIn().getBody(String::class.java)
            return DelayResultInventory("$body|$computed")
        }
    }

    @Test
    fun `suspend function with real delay and dispatcher hop completes correctly`() {
        val support = GuanacoTestSupport("io.github.lilaschuda.guanaco.kotlin")
            .route(
                "DelayingProcessor",
                "direct:orders",
                mapOf("DelayResultInventory" to listOf(BindingTarget().apply { uri = "mock:inventory" }))
            )

        val env = support.start()
        try {
            val mock = env.getMock("mock:inventory")
            mock.expectedMessageCount(1)

            env.send("direct:orders", "hello")

            mock.assertIsSatisfied()
            val received = mock.exchanges[0].getIn().getBody(String::class.java)
            assertThat(received).isEqualTo("hello|resumed-after-delay")
        } finally {
            env.shutdown()
        }
    }
}