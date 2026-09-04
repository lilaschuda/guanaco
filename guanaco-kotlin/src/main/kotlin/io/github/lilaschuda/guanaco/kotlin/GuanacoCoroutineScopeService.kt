package io.github.lilaschuda.guanaco.kotlin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.apache.camel.Service

/**
 * Holds the CoroutineScope backing every AsyncOutcomeProcessor bridge for
 * one CamelContext. Registered via CamelContext.addService(...) so its own
 * start()/stop() lifecycle is tied to the owning context's -- Camel calls
 * stop() automatically when the context stops, which cancels every
 * still-running coroutine launched through this scope.
 *
 * One instance per context, found-or-created lazily on first use via
 * context.hasService(...) -- see [SuspendOutcomeProcessor]. Never
 * constructed directly by user code, so this stays internal rather than
 * part of guanaco-kotlin's public API.
 */
internal class GuanacoCoroutineScopeService : Service {
    private val job = SupervisorJob()
    val scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default)

    override fun start() {
        // Nothing to do -- the scope is ready to use from construction.
    }

    override fun stop() {
        job.cancel()
    }
}