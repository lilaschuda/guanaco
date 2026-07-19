package io.github.lilaschuda.guanaco.config;

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

    private String correlationHeader;
    private String strategyRef;
    private Integer completionSize;
    private Long completionTimeoutMs;

    public String getCorrelationHeader() { return correlationHeader; }
    public void setCorrelationHeader(String correlationHeader) { this.correlationHeader = correlationHeader; }

    public String getStrategyRef() { return strategyRef; }
    public void setStrategyRef(String strategyRef) { this.strategyRef = strategyRef; }

    public Integer getCompletionSize() { return completionSize; }
    public void setCompletionSize(Integer completionSize) { this.completionSize = completionSize; }

    public Long getCompletionTimeoutMs() { return completionTimeoutMs; }
    public void setCompletionTimeoutMs(Long completionTimeoutMs) { this.completionTimeoutMs = completionTimeoutMs; }
}