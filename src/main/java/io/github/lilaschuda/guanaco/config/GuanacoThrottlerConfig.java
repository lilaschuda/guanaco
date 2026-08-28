package io.github.lilaschuda.guanaco.config;

/**
 * Throttling policy — declarable at route level (default for every binding
 * on that route) or per-binding (overrides the route default for that one
 * target), using the exact same hierarchy as GuanacoCircuitBreakerConfig.
 *
 * <p>When both a throttler and a circuitBreaker apply to the same binding,
 * throttling wraps outermost: admission control (should this call even be
 * attempted right now) happens before failure detection (did the attempted
 * call succeed). This ordering is fixed and not configurable.
 *
 * <p>asyncDelayed and rejectExecution are mutually exclusive — rejected at
 * boot if both are true, since "never wait" and "wait without blocking"
 * are contradictory. Neither set: Camel's default blocking queue-and-wait.
 */
public class GuanacoThrottlerConfig {

    private Boolean enabled;
    private Integer requestsPerPeriod;
    private Long timePeriodMillis;
    private Boolean asyncDelayed;
    private Boolean rejectExecution;

    /** Default constructor, used by Jackson when deserializing a throttler block. */
    public GuanacoThrottlerConfig() { }

    /** @return the explicitly configured enabled state, or {@code null} if not set */
    public Boolean getEnabled() { return enabled; }
    /** @param enabled whether this throttling policy is active */
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    /**
     * @return the effective enabled state — {@code true} unless explicitly set to
     *         {@code false}, so an absent {@code enabled} field means "on."
     */
    public boolean resolveEnabled() {
        return enabled == null || enabled;
    }

    /** @return the configured maximum number of requests per period */
    public Integer getRequestsPerPeriod() { return requestsPerPeriod; }
    /** @param requestsPerPeriod the maximum number of requests allowed per period */
    public void setRequestsPerPeriod(Integer requestsPerPeriod) { this.requestsPerPeriod = requestsPerPeriod; }

    /** @return the configured throttling period length in milliseconds */
    public Long getTimePeriodMillis() { return timePeriodMillis; }
    /** @param timePeriodMillis the throttling period length in milliseconds */
    public void setTimePeriodMillis(Long timePeriodMillis) { this.timePeriodMillis = timePeriodMillis; }

    /** @return the explicitly configured async-delayed state, or {@code null} if not set */
    public Boolean getAsyncDelayed() { return asyncDelayed; }
    /** @param asyncDelayed whether over-limit calls wait asynchronously rather than blocking */
    public void setAsyncDelayed(Boolean asyncDelayed) { this.asyncDelayed = asyncDelayed; }

    /**
     * @return the effective async-delayed state — {@code false} unless explicitly
     *         set to {@code true}.
     */
    public boolean resolveAsyncDelayed() {
        return asyncDelayed != null && asyncDelayed;
    }

    /** @return the explicitly configured reject-execution state, or {@code null} if not set */
    public Boolean getRejectExecution() { return rejectExecution; }
    /** @param rejectExecution whether over-limit calls are rejected immediately rather than queued */
    public void setRejectExecution(Boolean rejectExecution) { this.rejectExecution = rejectExecution; }

    /**
     * @return the effective reject-execution state — {@code false} unless explicitly
     *         set to {@code true}.
     */
    public boolean resolveRejectExecution() {
        return rejectExecution != null && rejectExecution;
    }
}