package io.github.lilaschuda.guanaco.kotlin

import io.github.lilaschuda.guanaco.api.AsyncOutcomeProcessor
import io.github.lilaschuda.guanaco.api.OutcomeCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.apache.camel.Exchange

/**
 * Base class for authoring Guanaco routes with Kotlin suspend functions.
 * A @GuanacoRoute-annotated class extends this and implements
 * [processSuspending] instead of Processor's synchronous `process(Exchange): R`.
 *
 * The coroutine is launched on the owning context's own scope, found-or-
 * created lazily on first use and cancelled automatically when that
 * context stops -- see [GuanacoCoroutineScopeService]. Since @GuanacoRoute
 * classes are instantiated via a no-arg reflection constructor with no
 * dependency injection available, the scope can't be handed in at
 * construction time; it's looked up from the Exchange's own CamelContext
 * at each call instead, purely through Camel's own generic
 * hasService/addService API -- no dependency on GuanacoContext specifically.
 */
abstract class SuspendOutcomeProcessor<R : Any> : AsyncOutcomeProcessor<R> {

    /**
     * Like Processor.process, but suspending -- may call other suspend
     * functions, await I/O, etc. without blocking the calling thread.
     */
    abstract suspend fun processSuspending(exchange: Exchange): R

    final override fun process(exchange: Exchange, callback: OutcomeCallback<R>) {
        val context = exchange.context
        val scopeService = context.hasService(GuanacoCoroutineScopeService::class.java)
            ?: synchronized(context) {
                context.hasService(GuanacoCoroutineScopeService::class.java)
                    ?: GuanacoCoroutineScopeService().also { context.addService(it) }
            }

        scopeService.scope.launch {
            try {
                val outcome = processSuspending(exchange)
                callback.onOutcome(outcome)
            } catch (e: CancellationException) {
                // Camel's ShutdownStrategy tracks this exchange as inflight
                // until callback.done(...) fires -- during shutdown it would
                // hang until its own timeout forces a kill if we didn't
                // report this. onFailure() sets the exception and calls
                // done(false), clean-completing the inflight tracking, which
                // is standard Camel behavior for a cancelled-during-shutdown
                // exchange. Rethrown afterward so structured concurrency and
                // this Job's own cancelled state stay correct on the Kotlin
                // side -- swallowing a CancellationException instead of
                // letting it propagate is exactly the anti-pattern the
                // coroutines library warns against.
                callback.onFailure(e)
                throw e
            } catch (e: Throwable) {
                callback.onFailure(e)
            }
        }
    }
}