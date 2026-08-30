package io.github.lilaschuda.guanaco.api.telemetry;

import java.util.List;

/**
 * Event listener for tracking execution metrics, EIP operations, and fault tolerance events.
 *
 * <p><b>Not covered by the v1.0 API freeze</b> — see the {@code api.telemetry}
 * package documentation. Unwired from the engine as of v1.0; method
 * signatures may still change ahead of stabilization.
 */
public interface GuanacoTelemetryListener {

    /**
     * Invoked when an idempotent consumer processes a message.
     *
     * @param routeId target processor/route name
     * @param messageId evaluated message ID header
     * @param duplicate true if the message was recognized as a duplicate and skipped
     */
    default void onIdempotentEvaluation(String routeId, String messageId, boolean duplicate) {}

    /**
     * Invoked when a resequencer processes or drops a message.
     *
     * @param routeId target processor/route name
     * @param rejected true if the message was rejected due to being out of sequence (rejectOld)
     */
    default void onResequenceEvent(String routeId, boolean rejected) {}

    /**
     * Invoked when an aggregation bucket completes and emits its merged exchange.
     *
     * @param routeId target processor/route name
     * @param completionReason condition that triggered completion (e.g., "size", "timeout")
     */
    default void onAggregateComplete(String routeId, String completionReason) {}

    /**
     * Invoked when an injected delay is executed prior to binding dispatch.
     *
     * @param routeId target processor/route name
     * @param bindingTargetUri target URI where delay was applied
     * @param delayMs duration of delay in milliseconds
     */
    default void onDelayApplied(String routeId, String bindingTargetUri, long delayMs) {}

    /**
     * Invoked when a sealed outcome is successfully dispatched to a binding URI.
     *
     * @param routeId source processor/route name
     * @param outcomeType simple class name of the sealed permit outcome
     * @param targetUri destination binding URI
     * @param durationMs total execution time for the dispatch in milliseconds
     */
    default void onOutcomeDispatched(String routeId, String outcomeType, String targetUri, long durationMs) {}

    /**
     * Invoked when an outcome dispatch fails or trips a circuit breaker.
     *
     * @param routeId source processor/route name
     * @param targetUri destination binding URI
     * @param cause underlying exception
     */
    default void onOutcomeFailed(String routeId, String targetUri, Throwable cause) {}
    
    /**
     * Returns a snapshot of recent failure records captured by this listener.
     *
     * @return an unmodifiable list of recent failure records
     */
    List<FailureRecord> recentFailures();
}