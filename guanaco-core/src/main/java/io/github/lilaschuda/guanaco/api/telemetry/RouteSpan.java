package io.github.lilaschuda.guanaco.api.telemetry;

/**
 * Immutable snapshot of one node an exchange passed through, as
 * captured by Camel's own message history mechanism
 * ({@code Exchange.CamelMessageHistory}) and reported by a
 * {@link GuanacoTelemetryListener}.
 *
 * <p>Deliberately a Guanaco-owned type rather than exposing Camel's own
 * {@code org.apache.camel.MessageHistory}/{@code NamedNode} directly on
 * this public interface — same reasoning as {@link FailureRecord} not
 * exposing raw exception-wrapper types.
 *
 * @param routeId the Camel route id the exchange was in at this point
 * @param nodeId the id of the node visited (e.g. {@code "to1"}, often auto-generated unless explicitly set)
 * @param nodeType the short, human-readable node type (e.g. {@code "to"}, {@code "idempotentConsumer"})
 * @param elapsedMs how long processing at this node took, in milliseconds
 */
public record RouteSpan(
        String routeId,
        String nodeId,
        String nodeType,
        long elapsedMs
) {}
