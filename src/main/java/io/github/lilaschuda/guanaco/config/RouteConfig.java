package io.github.lilaschuda.guanaco.config;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RouteConfig {

    private String from;

    @JsonDeserialize(using = BindingsDeserializer.class)
    private Map<String, List<BindingTarget>> bindings = new LinkedHashMap<>();

    private ErrorHandlerConfig errorHandler;
    private GuanacoAggregateConfig aggregate;
    private GuanacoIdempotentConfig idempotent;
    private GuanacoResequenceConfig resequence;

    /** Route-level default circuit breaker policy — inherited by every binding unless overridden. */
    private GuanacoCircuitBreakerConfig circuitBreaker;
    private GuanacoThrottlerConfig throttler;
    private GuanacoDelayerConfig delayer;

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public Map<String, List<BindingTarget>> getBindings() { return bindings; }
    public void setBindings(Map<String, List<BindingTarget>> bindings) { this.bindings = bindings; }

    public ErrorHandlerConfig getErrorHandler() { return errorHandler; }
    public void setErrorHandler(ErrorHandlerConfig errorHandler) { this.errorHandler = errorHandler; }

    public GuanacoAggregateConfig getAggregate() { return aggregate; }
    public void setAggregate(GuanacoAggregateConfig aggregate) { this.aggregate = aggregate; }

    public GuanacoIdempotentConfig getIdempotent() { return idempotent; }
    public void setIdempotent(GuanacoIdempotentConfig idempotent) { this.idempotent = idempotent; }

    public GuanacoResequenceConfig getResequence() { return resequence; }
    public void setResequence(GuanacoResequenceConfig resequence) { this.resequence = resequence; }

    public GuanacoCircuitBreakerConfig getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(GuanacoCircuitBreakerConfig circuitBreaker) { this.circuitBreaker = circuitBreaker; }

    public GuanacoThrottlerConfig getThrottler() { return throttler; }
    public void setThrottler(GuanacoThrottlerConfig throttler) { this.throttler = throttler; }

    public GuanacoDelayerConfig getDelayer() { return delayer; }
    public void setDelayer(GuanacoDelayerConfig delayer) { this.delayer = delayer; }

    public GuanacoDelayerConfig resolveDelayerFor(BindingTarget target) {
        if (target.getDelayer() != null) {
            return target.getDelayer().resolveEnabled() ? target.getDelayer() : null;
        }
        return delayer;
    }
 
    /** Just the URIs for a given outcome — what dispatch code (Multicast/Split/fanOut) actually needs. */
    public List<String> getUrisFor(String outcomeName) {
        List<BindingTarget> targets = bindings.get(outcomeName);
        if (targets == null) return null;
        return targets.stream().map(BindingTarget::getUri).toList();
    }

    /**
     * Effective circuit breaker for one binding target: its own override
     * (if not explicitly disabled), else the route-level default (which may
     * itself be null — no policy at all).
     */
    public GuanacoCircuitBreakerConfig resolveCircuitBreakerFor(BindingTarget target) {
        if (target.getCircuitBreaker() != null) {
            return target.getCircuitBreaker().resolveEnabled() ? target.getCircuitBreaker() : null;
        }
        return circuitBreaker;
    }

    public GuanacoThrottlerConfig resolveThrottlerFor(BindingTarget target) {
        if (target.getThrottler() != null) {
            return target.getThrottler().resolveEnabled() ? target.getThrottler() : null;
        }
        return throttler;
    }

    public static class ErrorHandlerConfig {
        private String deadLetter;
        private int maxRetries = 0;

        public String getDeadLetter() { return deadLetter; }
        public void setDeadLetter(String deadLetter) { this.deadLetter = deadLetter; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }
}