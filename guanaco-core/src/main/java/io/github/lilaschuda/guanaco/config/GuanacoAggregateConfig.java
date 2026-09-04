package io.github.lilaschuda.guanaco.config;

import org.jspecify.annotations.Nullable;

/**
 * Optional aggregation configuration for a route, declared as a nested
 * {@code aggregate:} block within a RouteConfig — modeled identically to how
 * {@code errorHandler} already works, not as a polymorphic "step" pipeline.
 *
 * <p>When present, incoming messages are correlated by {@code correlationHeader}
 * and merged using the {@link org.apache.camel.AggregationStrategy} registered
 * under {@code strategyRef} (via {@code GuanacoContext.registerAggregationStrategy}),
 * before ever reaching the route's processor. At least one completion
 * condition ({@code completionSize} or {@code completionTimeoutMs}) must be set.
 *
 * <p>{@code correlationHeader} is resolved internally via Camel's type-safe
 * {@code header(name)} expression builder — a plain header name, not an
 * interpreted expression language string. This keeps aggregation fully
 * inside Guanaco's closed-world, no-dynamic-evaluation spirit.
 */
public class GuanacoAggregateConfig {

    private @Nullable String correlationHeader;
    private @Nullable String strategyRef;
    private @Nullable Integer completionSize;
    private @Nullable Long completionTimeoutMs;

    /** Default constructor, used by Jackson when deserializing an aggregate block. */
    public GuanacoAggregateConfig() { }

    /**
     * Gets the header used to correlate messages into the same aggregation group.
     *
     * @return the header used to correlate messages into the same aggregation group,
     *         or {@code null} if not set
     */
    public @Nullable String getCorrelationHeader() { return correlationHeader; }

    /**
     * Sets the header used to correlate messages into the same aggregation group.
     *
     * @param correlationHeader the header used to correlate messages into the same aggregation group
     */
    public void setCorrelationHeader(@Nullable String correlationHeader) { this.correlationHeader = correlationHeader; }

    /**
     * Gets the name of the registered aggregation strategy.
     *
     * @return the name of the registered {@link org.apache.camel.AggregationStrategy} to merge with,
     *         or {@code null} if not set
     */
    public @Nullable String getStrategyRef() { return strategyRef; }

    /**
     * Sets the name of the registered aggregation strategy.
     *
     * @param strategyRef the name of the registered {@link org.apache.camel.AggregationStrategy} to merge with
     */
    public void setStrategyRef(@Nullable String strategyRef) { this.strategyRef = strategyRef; }

    /**
     * Gets the message-count completion threshold.
     *
     * @return the message-count completion threshold, or {@code null} if not used
     */
    public @Nullable Integer getCompletionSize() { return completionSize; }

    /**
     * Sets the message-count completion threshold.
     *
     * @param completionSize the message-count completion threshold
     */
    public void setCompletionSize(@Nullable Integer completionSize) { this.completionSize = completionSize; }

    /**
     * Gets the timeout-based completion threshold.
     *
     * @return the timeout-based completion threshold in milliseconds, or {@code null} if not used
     */
    public @Nullable Long getCompletionTimeoutMs() { return completionTimeoutMs; }

    /**
     * Sets the timeout-based completion threshold.
     *
     * @param completionTimeoutMs the timeout-based completion threshold in milliseconds
     */
    public void setCompletionTimeoutMs(@Nullable Long completionTimeoutMs) { this.completionTimeoutMs = completionTimeoutMs; }
}