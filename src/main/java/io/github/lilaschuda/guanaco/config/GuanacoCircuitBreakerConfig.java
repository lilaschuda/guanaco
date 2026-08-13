package io.github.lilaschuda.guanaco.config;

/**
 * Circuit breaker policy — declarable at route level (default for every
 * binding on that route) or per-binding (overrides the route default for
 * that one target). A binding may set {@code enabled: false} to opt out of
 * an inherited route-level policy entirely.
 *
 * <p>Applies only to bindings reached through the standard choice()
 * dispatch table — i.e. outcomes that are permitted subtypes of the
 * processor's own sealed hierarchy. Multicast/Split dispatch via
 * {@code producerTemplate.send()}, which has no Camel DSL node to wrap;
 * see BindingValidator's boot-time rejection for the fully-decidable case
 * (a non-sealed-hierarchy outcome), and the README for the residual,
 * undecidable case (a sealed-hierarchy outcome ALSO emitted via
 * Multicast/Split by developer code — the policy silently won't apply on
 * that path).
 */
public class GuanacoCircuitBreakerConfig {

    private Boolean enabled;
    private Integer timeoutDurationMs;
    private Integer slidingWindowSize;
    private Integer minimumNumberOfCalls;
    private Integer failureRateThreshold;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public boolean resolveEnabled() {
        return enabled == null || enabled;
    }

    public Integer getTimeoutDurationMs() { return timeoutDurationMs; }
    public void setTimeoutDurationMs(Integer timeoutDurationMs) { this.timeoutDurationMs = timeoutDurationMs; }

    public Integer getSlidingWindowSize() { return slidingWindowSize; }
    public void setSlidingWindowSize(Integer slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }

    public Integer getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
    public void setMinimumNumberOfCalls(Integer minimumNumberOfCalls) { this.minimumNumberOfCalls = minimumNumberOfCalls; }

    public Integer getFailureRateThreshold() { return failureRateThreshold; }
    public void setFailureRateThreshold(Integer failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }
}