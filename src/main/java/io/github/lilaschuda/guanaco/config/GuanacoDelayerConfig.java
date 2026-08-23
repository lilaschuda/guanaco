package io.github.lilaschuda.guanaco.config;

/**
 * Delay policy — declarable at route level (default for every binding on
 * that route) or per-binding (overrides the route default for that one
 * target), using the same hierarchy as GuanacoCircuitBreakerConfig and
 * GuanacoThrottlerConfig.
 *
 * <p>Exactly one of delayMs or delayStrategyRef must be set — these are
 * alternative sources for the same single value, not independent
 * conditions (unlike Aggregate's "at least one of two completion
 * conditions"). delayMs is a fixed constant; delayStrategyRef points to a
 * registered GuanacoDelayStrategy for a computed, per-exchange delay.
 *
 * <p>When combined with throttler/circuitBreaker on the same binding, the
 * fixed, non-configurable ordering is Throttle (outermost) → Delay →
 * Circuit Breaker (innermost). Delay sits between the two deliberately:
 * nesting it inside Circuit Breaker would count the artificial pause
 * toward the circuit breaker's own timeout measurement, which could trip
 * the breaker purely because of Guanaco's own injected delay rather than
 * genuine downstream latency.
 *
 * <p>asyncDelayed defaults to false, matching Camel's own native default
 * (blocking) — NOT silently overridden to non-blocking. A large delayMs
 * with asyncDelayed left false will block the calling route thread for
 * the full duration; set asyncDelayed: true explicitly for any
 * production hot path where that matters.
 */
public class GuanacoDelayerConfig {

    private Boolean enabled;
    private Long delayMs;
    private String delayStrategyRef;
    private Boolean asyncDelayed;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public boolean resolveEnabled() {
        return enabled == null || enabled;
    }

    public Long getDelayMs() { return delayMs; }
    public void setDelayMs(Long delayMs) { this.delayMs = delayMs; }

    public String getDelayStrategyRef() { return delayStrategyRef; }
    public void setDelayStrategyRef(String delayStrategyRef) { this.delayStrategyRef = delayStrategyRef; }

    public Boolean getAsyncDelayed() { return asyncDelayed; }
    public void setAsyncDelayed(Boolean asyncDelayed) { this.asyncDelayed = asyncDelayed; }

    public boolean resolveAsyncDelayed() {
        return asyncDelayed != null && asyncDelayed;
    }
}