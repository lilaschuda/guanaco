package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.eip.Split;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges Camel's native splitter aggregation engine to the optional,
 * user-supplied AggregationStrategy carried on a Split outcome.
 *
 * Registered once per route at configure time. At runtime, looks up the
 * active Split from the exchange property to find the real strategy to
 * delegate to. If the Split carries no strategy, defaults to Camel's
 * standard "keep the newest exchange" behavior — i.e. split-and-forget.
 */
class GuanacoDelegatingAggregationStrategy implements AggregationStrategy {

    private static final Logger log = LoggerFactory.getLogger(GuanacoDelegatingAggregationStrategy.class);

    private final String outcomeProperty;
    private final String processorName;

    GuanacoDelegatingAggregationStrategy(String outcomeProperty, String processorName) {
        this.outcomeProperty = outcomeProperty;
        this.processorName = processorName;
    }

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        AggregationStrategy delegate = resolveDelegate(newExchange);

        if (delegate != null) {
            return delegate.aggregate(oldExchange, newExchange);
        }

        // No user strategy supplied — split-and-forget default.
        return newExchange;
    }

    private AggregationStrategy resolveDelegate(Exchange exchange) {
        Object outcome = exchange.getProperty(outcomeProperty);
        if (outcome instanceof Split split && split.aggregationStrategy() != null) {
            return split.aggregationStrategy();
        }
        log.debug("[{}] Split has no aggregationStrategy — defaulting to split-and-forget", processorName);
        return null;
    }
}