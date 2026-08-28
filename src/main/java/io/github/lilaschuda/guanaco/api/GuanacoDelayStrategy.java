package io.github.lilaschuda.guanaco.api;

import org.apache.camel.Exchange;

/**
 * A compiled, per-exchange delay computation — the type-safe alternative to
 * a fixed delayMs when the pause needs to depend on message content
 * (e.g. an exponential backoff based on a retry-count header).
 *
 * Registered by name via GuanacoContext.registerDelayStrategy(...), same
 * pattern as AggregationStrategy — no Spring bean lookup, no reflection,
 * no interpreted expression string.
 */
public interface GuanacoDelayStrategy {

    /**
     * Computes the delay to apply for the given exchange.
     *
     * @param exchange the Camel exchange the delay is being computed for
     * @return the delay, in milliseconds
     */
    long computeDelayMs(Exchange exchange);
}