package io.github.lilaschuda.guanaco.api.telemetry;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Immutable snapshot of a failure event captured by a {@link GuanacoTelemetryListener}.
 *
 * @param timestamp        the timestamp when the failure occurred
 * @param processorName    the name of the processor handling the route
 * @param targetUri        the endpoint URI where the failure took place
 * @param exceptionType    the simple class name of the exception, or "Unknown"
 * @param exceptionMessage the detail message of the exception, if available
 */
public record FailureRecord(
        Instant timestamp,
        String processorName,
        String targetUri,
        String exceptionType,
        @Nullable String exceptionMessage
) {}