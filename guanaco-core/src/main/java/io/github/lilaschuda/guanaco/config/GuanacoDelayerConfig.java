package io.github.lilaschuda.guanaco.config;

import org.jspecify.annotations.Nullable;

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

    private @Nullable Boolean enabled;
    private @Nullable Long delayMs;
    private @Nullable String delayStrategyRef;
    private @Nullable Boolean asyncDelayed;

    /** Default constructor, used by Jackson when deserializing a delayer block. */
    public GuanacoDelayerConfig() { }

    /**
     * Gets the explicitly configured enabled state.
     *
     * @return the explicitly configured enabled state, or {@code null} if not set
     */
    public @Nullable Boolean getEnabled() { return enabled; }

    /**
     * Sets whether this delay policy is active.
     *
     * @param enabled whether this delay policy is active
     */
    public void setEnabled(@Nullable Boolean enabled) { this.enabled = enabled; }

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
     * Gets the fixed delay in milliseconds.
     *
     * @return the fixed delay in milliseconds, or {@code null} if a {@code delayStrategyRef} is used instead
     */
    public @Nullable Long getDelayMs() { return delayMs; }

    /**
     * Sets the fixed delay in milliseconds.
     *
     * @param delayMs the fixed delay in milliseconds
     */
    public void setDelayMs(@Nullable Long delayMs) { this.delayMs = delayMs; }

    /**
     * Gets the name of the registered delay strategy.
     *
     * @return the name of the registered {@link io.github.lilaschuda.guanaco.api.GuanacoDelayStrategy}
     *         to compute the delay with, or {@code null} if a fixed {@code delayMs} is used instead
     */
    public @Nullable String getDelayStrategyRef() { return delayStrategyRef; }

    /**
     * Sets the name of the registered delay strategy.
     *
     * @param delayStrategyRef the name of the registered delay strategy to compute the delay with
     */
    public void setDelayStrategyRef(@Nullable String delayStrategyRef) { this.delayStrategyRef = delayStrategyRef; }

    /**
     * Gets the explicitly configured async-delayed state.
     *
     * @return the explicitly configured async-delayed state, or {@code null} if not set
     */
    public @Nullable Boolean getAsyncDelayed() { return asyncDelayed; }

    /**
     * Sets whether the delay should be non-blocking.
     *
     * @param asyncDelayed whether the delay should be non-blocking
     */
    public void setAsyncDelayed(@Nullable Boolean asyncDelayed) { this.asyncDelayed = asyncDelayed; }

    /**
     * Resolves the effective async-delayed state.
     *
     * @return the effective async-delayed state — {@code false} (blocking) unless
     *         explicitly set to {@code true}, matching Camel's own native default.
     */
    public boolean resolveAsyncDelayed() {
        return asyncDelayed != null && asyncDelayed;
    }
}