package io.github.lilaschuda.guanaco.api.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer-backed implementation of {@link GuanacoTelemetryListener}.
 */
public class GuanacoMicrometerListener implements GuanacoTelemetryListener {

    private final MeterRegistry registry;

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
    public void onOutcomeFailed(String routeId, String targetUri, Throwable cause) {
        Counter.builder("guanaco.outcome.failures")
                .tag("route", routeId)
                .tag("target", targetUri)
                .tag("exception", cause.getClass().getSimpleName())
                .register(registry)
                .increment();
    }
}