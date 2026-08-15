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

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public boolean resolveEnabled() {
        return enabled == null || enabled;
    }

    public Integer getRequestsPerPeriod() { return requestsPerPeriod; }
    public void setRequestsPerPeriod(Integer requestsPerPeriod) { this.requestsPerPeriod = requestsPerPeriod; }

    public Long getTimePeriodMillis() { return timePeriodMillis; }
    public void setTimePeriodMillis(Long timePeriodMillis) { this.timePeriodMillis = timePeriodMillis; }

    public Boolean getAsyncDelayed() { return asyncDelayed; }
    public void setAsyncDelayed(Boolean asyncDelayed) { this.asyncDelayed = asyncDelayed; }

    public boolean resolveAsyncDelayed() {
        return asyncDelayed != null && asyncDelayed;
    }

    public Boolean getRejectExecution() { return rejectExecution; }
    public void setRejectExecution(Boolean rejectExecution) { this.rejectExecution = rejectExecution; }

    public boolean resolveRejectExecution() {
        return rejectExecution != null && rejectExecution;
    }
}