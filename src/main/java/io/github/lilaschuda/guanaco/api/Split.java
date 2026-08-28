package io.github.lilaschuda.guanaco.api;

import io.github.lilaschuda.guanaco.api.RouteOutcome;

import java.util.List;
import org.apache.camel.AggregationStrategy;

/**
 * Decomposes one message into multiple independent RouteOutcome items, each
 * routed as if it had arrived as its own standalone outcome — reusing the
 * existing choice() dispatch table and routes.yaml bindings by simple class
 * name. No YAML configuration is required for Split itself.
 *
 * Items are heterogeneous: each may be a different RouteOutcome subtype,
 * destined for a different endpoint, per its own binding.
 *
 * By default, split-and-forget — no aggregation, each item is dispatched
 * independently and results are not collected. An optional Camel
 * AggregationStrategy may be supplied to collect results using Camel's
 * native splitter aggregation engine.
 *
 * Usage:
 *   return new Split(List.of(new ToMainframeWarehouse(item1), new ToAuditLog(item2)));
 *   return new Split(items, myAggregationStrategy);
 */
public final class Split implements RouteOutcome<List<? extends RouteOutcome<?>>> {

    private final List<? extends RouteOutcome<?>> items;
    private final AggregationStrategy aggregationStrategy;

    /**
     * Creates a split outcome with split-and-forget semantics (no aggregation).
     *
     * @param items the individual routing outcome items to dispatch
     */
    public Split(List<? extends RouteOutcome<?>> items) {
        this(items, null);
    }

    /**
     * Creates a split outcome with optional native Camel result aggregation.
     *
     * @param items the individual routing outcome items to dispatch
     * @param aggregationStrategy the strategy used to aggregate results, or {@code null} for split-and-forget
     */
    public Split(List<? extends RouteOutcome<?>> items, AggregationStrategy aggregationStrategy) {
        this.items = List.copyOf(items);
        this.aggregationStrategy = aggregationStrategy;
    }

    @Override
    public List<? extends RouteOutcome<?>> body() {
        return items;
    }

    /**
     * Returns the individual outcome items produced by this split operation.
     *
     * @return the list of split outcome items
     */
    public List<? extends RouteOutcome<?>> items() {
        return items;
    }

    /**
     * Returns the aggregation strategy configured for this split, if any.
     *
     * @return the {@link AggregationStrategy}, or {@code null} if split-and-forget
     */
    public AggregationStrategy aggregationStrategy() {
        return aggregationStrategy;
    }
}