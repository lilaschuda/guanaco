package io.github.lilaschuda.guanaco.config;

import org.jspecify.annotations.Nullable;

/**
 * A single binding destination: a URI, plus an optional per-binding
 * circuitBreaker override. Accepted in routes.yaml/json as either a plain
 * string ("jms:queue:audit" — uri only, inherits route-level policy) or a
 * rich object ({ uri: "...", circuitBreaker: {...} }) — see
 * {@link BindingsDeserializer}.
 */
public class BindingTarget {

    private @Nullable String uri;
    private @Nullable GuanacoCircuitBreakerConfig circuitBreaker;
    private @Nullable GuanacoThrottlerConfig throttler;
    private @Nullable GuanacoDelayerConfig delayer;

    /**
     * This binding's egress sample policy — applied only during dispatch
     * to this one destination, after the rest of the route already ran.
     * Independent of any route-level sample policy; does not inherit from
     * or override it. See {@link GuanacoSampleConfig}.
     */
    private @Nullable GuanacoSampleConfig sample;

    /** Default constructor, used by Jackson when deserializing a binding target. */
    public BindingTarget() { }

    /**
     * Gets the endpoint URI for this binding.
     *
     * @return the endpoint URI for this binding, or {@code null} if a
     *         structurally incomplete binding entry omitted it
     */
    public @Nullable String getUri() { return uri; }

    /**
     * Sets the endpoint URI for this binding.
     *
     * @param uri the endpoint URI for this binding
     */
    public void setUri(@Nullable String uri) { this.uri = uri; }

    /**
     * Gets this binding's circuit breaker override.
     *
     * @return this binding's circuit breaker override, or {@code null} to inherit the route default
     */
    public @Nullable GuanacoCircuitBreakerConfig getCircuitBreaker() { return circuitBreaker; }

    /**
     * Sets this binding's circuit breaker override.
     *
     * @param circuitBreaker this binding's circuit breaker override, or {@code null} to inherit the route default
     */
    public void setCircuitBreaker(@Nullable GuanacoCircuitBreakerConfig circuitBreaker) { this.circuitBreaker = circuitBreaker; }

    /**
     * Gets this binding's throttler override.
     *
     * @return this binding's throttler override, or {@code null} to inherit the route default
     */
    public @Nullable GuanacoThrottlerConfig getThrottler() { return throttler; }

    /**
     * Sets this binding's throttler override.
     *
     * @param throttler this binding's throttler override, or {@code null} to inherit the route default
     */
    public void setThrottler(@Nullable GuanacoThrottlerConfig throttler) { this.throttler = throttler; }

    /**
     * Gets this binding's delayer override.
     *
     * @return this binding's delayer override, or {@code null} to inherit the route default
     */
    public @Nullable GuanacoDelayerConfig getDelayer() { return delayer; }

    /**
     * Sets this binding's delayer override.
     *
     * @param delayer this binding's delayer override, or {@code null} to inherit the route default
     */
    public void setDelayer(@Nullable GuanacoDelayerConfig delayer) { this.delayer = delayer; }

    /**
     * Gets this binding's egress sample policy.
     *
     * @return this binding's egress sample policy, or {@code null} if not used
     */
    public @Nullable GuanacoSampleConfig getSample() { return sample; }

    /**
     * Sets this binding's egress sample policy.
     *
     * @param sample this binding's egress sample policy
     */
    public void setSample(@Nullable GuanacoSampleConfig sample) { this.sample = sample; }
}