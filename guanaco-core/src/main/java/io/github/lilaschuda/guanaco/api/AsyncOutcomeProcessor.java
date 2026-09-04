package io.github.lilaschuda.guanaco.api;

import org.apache.camel.Exchange;

/**
 * Alternative contract to {@link Processor} for route logic that needs to
 * await something without blocking the calling thread -- e.g. a suspend
 * function bridged in by the optional {@code guanaco-kotlin} module, or a
 * genuinely async I/O call written directly in Java.
 *
 * <p>Recognized by the same topology-inspection and route-wiring machinery
 * as {@link Processor}: a {@link GuanacoRoute}-annotated class may implement
 * either this or {@link Processor}, never both, and {@code R} is subject to
 * the exact same sealed-hierarchy requirement.
 *
 * <p>Implementations MUST call exactly one of {@link OutcomeCallback}'s
 * methods, exactly once, for every invocation -- calling neither leaves the
 * exchange permanently unresolved; calling either more than once is
 * undefined behavior.
 *
 * @param <R> the sealed {@link RouteOutcome} hierarchy declaring this processor's
 *        possible routing outcomes -- see {@link Processor}
 */
public interface AsyncOutcomeProcessor<R> {

    /**
     * Process the incoming Camel exchange and report a routing decision
     * asynchronously via {@code callback}, once it's available.
     *
     * @param exchange the Camel exchange carrying the message
     * @param callback reports this processor's eventual outcome or failure;
     *        must be called exactly once
     */
    void process(Exchange exchange, OutcomeCallback<R> callback);
}