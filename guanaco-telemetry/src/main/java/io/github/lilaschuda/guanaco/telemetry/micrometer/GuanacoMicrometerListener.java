package io.github.lilaschuda.guanaco.telemetry.micrometer;

import io.github.lilaschuda.guanaco.api.telemetry.FailureRecord;
import io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer-backed implementation of {@link GuanacoTelemetryListener}.
 *
 * <p>Ships in the separate {@code guanaco-telemetry} module. Guanaco's core
 * module never depends on Micrometer — nothing is pulled onto a consumer's
 * classpath unless this module is added as an explicit dependency.
 *
 * <p>Not registered automatically: construct an instance and pass it to
 * {@code GuanacoContext.registerTelemetryListener(...)} before calling
 * {@code wireRoutes()}.
 */
public class GuanacoMicrometerListener implements GuanacoTelemetryListener {

    private static final int MAX_RECENT_FAILURES = 100;
    
    private final MeterRegistry registry;
    private final Queue<FailureRecord> failureLog = new ConcurrentLinkedQueue<>();

    /**
     * Constructs a telemetry listener backed by the provided Micrometer registry.
     *
     * @param registry the Micrometer MeterRegistry to publish metrics to
     */
    public GuanacoMicrometerListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onIdempotentEvaluation(String routeId, String messageId, boolean duplicate) {
        Counter.builder("guanaco.idempotent.evaluations")
                .tag("route", routeId)
                .tag("result", duplicate ? "duplicate" : "original")
                .register(registry)
                .increment();
    }

    @Override
    public void onResequenceEvent(String routeId, boolean rejected) {
        Counter.builder("guanaco.resequence.events")
                .tag("route", routeId)
                .tag("status", rejected ? "rejected" : "processed")
                .register(registry)
                .increment();
    }

    @Override
    public void onAggregateComplete(String routeId, String completionReason) {
        Counter.builder("guanaco.aggregate.completions")
                .tag("route", routeId)
                .tag("reason", completionReason)
                .register(registry)
                .increment();
    }

    @Override
    public void onDelayApplied(String routeId, String bindingTargetUri, long delayMs) {
        Timer.builder("guanaco.delayer.execution")
                .tag("route", routeId)
                .tag("target", bindingTargetUri)
                .register(registry)
                .record(delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onOutcomeDispatched(String routeId, String outcomeType, String targetUri, long durationMs) {
        Timer.builder("guanaco.outcome.dispatch")
                .tag("route", routeId)
                .tag("outcome", outcomeType)
                .tag("target", targetUri)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onOutcomeFailed(String processorName, String targetUri, Throwable cause) {
        String exceptionType = cause != null ? cause.getClass().getSimpleName() : "Unknown";
        String exceptionMessage = cause != null ? cause.getMessage() : null;

        // 1. Record Micrometer counter
        registry.counter("guanaco.outcome.failures",
                "route", processorName,
                "target", targetUri,
                "exception", exceptionType
        ).increment();

        // 2. Log recent failure record (bounded thread-safe ring buffer)
        FailureRecord record = new FailureRecord(
                Instant.now(),
                processorName,
                targetUri,
                exceptionType,
                exceptionMessage
        );

        failureLog.add(record);
        while (failureLog.size() > MAX_RECENT_FAILURES) {
            failureLog.poll();
        }
    }

    @Override
    public List<FailureRecord> recentFailures() {
        return List.copyOf(failureLog);
    }
}