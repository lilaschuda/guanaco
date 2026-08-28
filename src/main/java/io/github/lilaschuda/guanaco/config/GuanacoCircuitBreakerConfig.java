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
    private Long waitDurationInOpenStateMs;

    /** Default constructor, used by Jackson when deserializing a circuit breaker block. */
    public GuanacoCircuitBreakerConfig() { }

    /**
     * Gets the explicitly configured enabled state.
     *
     * @return the explicitly configured enabled state, or {@code null} if not set
     */
    public Boolean getEnabled() { return enabled; }

    /**
     * Sets the active state for this circuit breaker policy.
     *
     * @param enabled whether this circuit breaker policy is active
     */
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    /**
     * Resolves the effective enabled state.
     *
     * @return the effective enabled state — {@code true} unless explicitly set to
     *         {@code false}, so an absent {@code enabled} field means "on."
     */
    public boolean resolveEnabled() {
        return enabled == null || enabled;
    }

    /**
     * Gets the Resilience4j call timeout in milliseconds.
     *
     * @return the configured Resilience4j call timeout in milliseconds
     */
    public Integer getTimeoutDurationMs() { return timeoutDurationMs; }

    /**
     * Sets the Resilience4j call timeout in milliseconds.
     *
     * @param timeoutDurationMs the Resilience4j call timeout in milliseconds
     */
    public void setTimeoutDurationMs(Integer timeoutDurationMs) { this.timeoutDurationMs = timeoutDurationMs; }

    /**
     * Gets the Resilience4j sliding window size.
     *
     * @return the configured Resilience4j sliding window size
     */
    public Integer getSlidingWindowSize() { return slidingWindowSize; }

    /**
     * Sets the Resilience4j sliding window size.
     *
     * @param slidingWindowSize the Resilience4j sliding window size
     */
    public void setSlidingWindowSize(Integer slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }

    /**
     * Gets the minimum number of calls required before failure rate calculation.
     *
     * @return the configured minimum number of calls before failure rate is calculated
     */
    public Integer getMinimumNumberOfCalls() { return minimumNumberOfCalls; }

    /**
     * Sets the minimum number of calls required before failure rate calculation.
     *
     * @param minimumNumberOfCalls the minimum number of calls before failure rate is calculated
     */
    public void setMinimumNumberOfCalls(Integer minimumNumberOfCalls) { this.minimumNumberOfCalls = minimumNumberOfCalls; }

    /**
     * Gets the failure rate threshold percentage that opens the circuit.
     *
     * @return the configured failure rate threshold percentage that opens the circuit
     */
    public Integer getFailureRateThreshold() { return failureRateThreshold; }

    /**
     * Sets the failure rate threshold percentage that opens the circuit.
     *
     * @param failureRateThreshold the failure rate threshold percentage that opens the circuit
     */
    public void setFailureRateThreshold(Integer failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }

    /**
     * Gets the wait duration in the open state before moving to half-open.
     *
     * @return the configured wait duration in the open state, in milliseconds, before moving to half-open
     */
    public Long getWaitDurationInOpenStateMs() { return waitDurationInOpenStateMs; }

    /**
     * Sets the wait duration in the open state before moving to half-open.
     *
     * @param waitDurationInOpenStateMs the wait duration in the open state, in milliseconds, before moving to half-open
     */
    public void setWaitDurationInOpenStateMs(Long waitDurationInOpenStateMs) { this.waitDurationInOpenStateMs = waitDurationInOpenStateMs; }
}