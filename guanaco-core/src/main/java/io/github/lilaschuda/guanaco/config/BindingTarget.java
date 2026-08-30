package io.github.lilaschuda.guanaco.config;

/**
 * A single binding destination: a URI, plus an optional per-binding
 * circuitBreaker override. Accepted in routes.yaml/json as either a plain
 * string ("jms:queue:audit" — uri only, inherits route-level policy) or a
 * rich object ({ uri: "...", circuitBreaker: {...} }) — see
 * {@link BindingsDeserializer}.
 */
public class BindingTarget {

    private String uri;
    private GuanacoCircuitBreakerConfig circuitBreaker;
    private GuanacoThrottlerConfig throttler;
    private GuanacoDelayerConfig delayer;

    /** Default constructor, used by Jackson when deserializing a binding target. */
    public BindingTarget() { }

    /**
     * Gets the endpoint URI for this binding.
     *
     * @return the endpoint URI for this binding
     */
    public String getUri() { return uri; }

    /**
     * Sets the endpoint URI for this binding.
     *
     * @param uri the endpoint URI for this binding
     */
    public void setUri(String uri) { this.uri = uri; }

    /**
     * Gets this binding's circuit breaker override.
     *
     * @return this binding's circuit breaker override, or {@code null} to inherit the route default
     */
    public GuanacoCircuitBreakerConfig getCircuitBreaker() { return circuitBreaker; }

    /**
     * Sets this binding's circuit breaker override.
     *
     * @param circuitBreaker this binding's circuit breaker override, or {@code null} to inherit the route default
     */
    public void setCircuitBreaker(GuanacoCircuitBreakerConfig circuitBreaker) { this.circuitBreaker = circuitBreaker; }

    /**
     * Gets this binding's throttler override.
     *
     * @return this binding's throttler override, or {@code null} to inherit the route default
     */
    public GuanacoThrottlerConfig getThrottler() { return throttler; }

    /**
     * Sets this binding's throttler override.
     *
     * @param throttler this binding's throttler override, or {@code null} to inherit the route default
     */
    public void setThrottler(GuanacoThrottlerConfig throttler) { this.throttler = throttler; }

    /**
     * Gets this binding's delayer override.
     *
     * @return this binding's delayer override, or {@code null} to inherit the route default
     */
    public GuanacoDelayerConfig getDelayer() { return delayer; }

    /**
     * Sets this binding's delayer override.
     *
     * @param delayer this binding's delayer override, or {@code null} to inherit the route default
     */
    public void setDelayer(GuanacoDelayerConfig delayer) { this.delayer = delayer; }
}