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

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }

    /** Null means "inherit the route-level circuitBreaker, if any." */
    public GuanacoCircuitBreakerConfig getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(GuanacoCircuitBreakerConfig circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    
    /** Null means "inherit the route-level throttler, if any." */
    public GuanacoThrottlerConfig getThrottler() { return throttler; }
    public void setThrottler(GuanacoThrottlerConfig throttler) { this.throttler = throttler; }

}