package io.github.lilaschuda.guanaco.config;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-route configuration: the {@code from} endpoint, its outcome-to-binding
 * mappings, and the optional EIP pipelines (idempotent consumer, resequencer,
 * aggregator) and per-outcome resilience policies (circuit breaker, throttler,
 * delayer) that wrap around it.
 *
 * <p>{@code bindings} maps each {@link io.github.lilaschuda.guanaco.api.RouteOutcome}
 * simple class name to one or more {@link BindingTarget} destinations — see
 * {@link BindingsDeserializer} for the accepted YAML/JSON shapes.
 *
 * <p>{@code circuitBreaker}, {@code throttler}, and {@code delayer} declared
 * here are route-level defaults, inherited by every binding unless a
 * {@link BindingTarget} declares its own override (including an explicit
 * {@code enabled: false} to opt out entirely) — see {@link #resolveCircuitBreakerFor},
 * {@link #resolveThrottlerFor}, and {@link #resolveDelayerFor}.
 */
public class RouteConfig {

    private @Nullable String from;

    @JsonDeserialize(using = BindingsDeserializer.class)
    private @Nullable Map<String, List<BindingTarget>> bindings = new LinkedHashMap<>();

    private @Nullable ErrorHandlerConfig errorHandler;
    private @Nullable GuanacoAggregateConfig aggregate;
    private @Nullable GuanacoIdempotentConfig idempotent;
    private @Nullable GuanacoResequenceConfig resequence;

    /**
     * Route-level ingress sample policy — applied once, before Idempotent,
     * to the inbound stream. Independent of any binding-level sample
     * policy; neither inherits from or overrides the other. See
     * {@link GuanacoSampleConfig}.
     */
    private @Nullable GuanacoSampleConfig sample;

    /**
     * Route-level pipeline thread handoff — a single async boundary applied
     * once, right after Sample and before Idempotent. See
     * {@link GuanacoThreadsConfig}.
     */
    private @Nullable GuanacoThreadsConfig threads;

    /**
     * Route-level Saga policy — wraps this route's dispatch (choice table
     * and everything downstream of Idempotent/Resequence/Aggregate) in
     * Camel's native {@code .saga()}. See {@link GuanacoSagaConfig}.
     */
    private @Nullable GuanacoSagaConfig saga;

    /** Route-level default circuit breaker policy — inherited by every binding unless overridden. */
    private @Nullable GuanacoCircuitBreakerConfig circuitBreaker;
    private @Nullable GuanacoThrottlerConfig throttler;
    private @Nullable GuanacoDelayerConfig delayer;

    /** Default constructor for Jackson deserialization. */
    public RouteConfig() { }

    /**
     * Gets the Camel {@code from} endpoint URI for this route.
     *
     * @return the Camel {@code from} endpoint URI for this route, or {@code null} if not set
     */
    public @Nullable String getFrom() { return from; }

    /**
     * Sets the Camel {@code from} endpoint URI for this route.
     *
     * @param from the Camel {@code from} endpoint URI for this route
     */
    public void setFrom(@Nullable String from) { this.from = from; }

    /**
     * Gets the outcome-simple-class-name to binding-target mapping for this route.
     *
     * @return the outcome-simple-class-name to binding-target mapping for this route,
     *         or {@code null} if explicitly set to null (e.g. {@code bindings: null} in YAML)
     */
    public @Nullable Map<String, List<BindingTarget>> getBindings() { return bindings; }

    /**
     * Sets the outcome-simple-class-name to binding-target mapping for this route.
     *
     * @param bindings the outcome-simple-class-name to binding-target mapping for this route
     */
    public void setBindings(@Nullable Map<String, List<BindingTarget>> bindings) { this.bindings = bindings; }

    /**
     * Gets this route's dead-letter/retry policy.
     *
     * @return this route's dead-letter/retry policy, or {@code null} if none is configured
     */
    public @Nullable ErrorHandlerConfig getErrorHandler() { return errorHandler; }

    /**
     * Sets this route's dead-letter/retry policy.
     *
     * @param errorHandler this route's dead-letter/retry policy
     */
    public void setErrorHandler(@Nullable ErrorHandlerConfig errorHandler) { this.errorHandler = errorHandler; }

    /**
     * Gets this route's aggregation configuration.
     *
     * @return this route's aggregation configuration, or {@code null} if aggregation isn't used
     */
    public @Nullable GuanacoAggregateConfig getAggregate() { return aggregate; }

    /**
     * Sets this route's aggregation configuration.
     *
     * @param aggregate this route's aggregation configuration
     */
    public void setAggregate(@Nullable GuanacoAggregateConfig aggregate) { this.aggregate = aggregate; }

    /**
     * Gets this route's idempotent-consumer configuration.
     *
     * @return this route's idempotent-consumer configuration, or {@code null} if not used
     */
    public @Nullable GuanacoIdempotentConfig getIdempotent() { return idempotent; }

    /**
     * Sets this route's idempotent-consumer configuration.
     *
     * @param idempotent this route's idempotent-consumer configuration
     */
    public void setIdempotent(@Nullable GuanacoIdempotentConfig idempotent) { this.idempotent = idempotent; }

    /**
     * Gets this route's resequencing configuration.
     *
     * @return this route's resequencing configuration, or {@code null} if not used
     */
    public @Nullable GuanacoResequenceConfig getResequence() { return resequence; }

    /**
     * Sets this route's resequencing configuration.
     *
     * @param resequence this route's resequencing configuration
     */
    public void setResequence(@Nullable GuanacoResequenceConfig resequence) { this.resequence = resequence; }

    /**
     * Gets this route's ingress sample policy.
     *
     * @return this route's ingress sample policy, or {@code null} if not used
     */
    public @Nullable GuanacoSampleConfig getSample() { return sample; }

    /**
     * Sets this route's ingress sample policy.
     *
     * @param sample this route's ingress sample policy
     */
    public void setSample(@Nullable GuanacoSampleConfig sample) { this.sample = sample; }

    /**
     * Gets this route's thread handoff policy.
     *
     * @return this route's thread handoff policy, or {@code null} if not used
     */
    public @Nullable GuanacoThreadsConfig getThreads() { return threads; }

    /**
     * Sets this route's thread handoff policy.
     *
     * @param threads this route's thread handoff policy
     */
    public void setThreads(@Nullable GuanacoThreadsConfig threads) { this.threads = threads; }

    /**
     * Gets this route's Saga policy.
     *
     * @return this route's Saga policy, or {@code null} if not used
     */
    public @Nullable GuanacoSagaConfig getSaga() { return saga; }

    /**
     * Sets this route's Saga policy.
     *
     * @param saga this route's Saga policy
     */
    public void setSaga(@Nullable GuanacoSagaConfig saga) { this.saga = saga; }

    /**
     * Gets this route's default circuit breaker policy.
     *
     * @return this route's default circuit breaker policy, or {@code null} if none is configured
     */
    public @Nullable GuanacoCircuitBreakerConfig getCircuitBreaker() { return circuitBreaker; }

    /**
     * Sets this route's default circuit breaker policy.
     *
     * @param circuitBreaker this route's default circuit breaker policy
     */
    public void setCircuitBreaker(@Nullable GuanacoCircuitBreakerConfig circuitBreaker) { this.circuitBreaker = circuitBreaker; }

    /**
     * Gets this route's default throttler policy.
     *
     * @return this route's default throttler policy, or {@code null} if none is configured
     */
    public @Nullable GuanacoThrottlerConfig getThrottler() { return throttler; }

    /**
     * Sets this route's default throttler policy.
     *
     * @param throttler this route's default throttler policy
     */
    public void setThrottler(@Nullable GuanacoThrottlerConfig throttler) { this.throttler = throttler; }

    /**
     * Gets this route's default delayer policy.
     *
     * @return this route's default delayer policy, or {@code null} if none is configured
     */
    public @Nullable GuanacoDelayerConfig getDelayer() { return delayer; }

    /**
     * Sets this route's default delayer policy.
     *
     * @param delayer this route's default delayer policy
     */
    public void setDelayer(@Nullable GuanacoDelayerConfig delayer) { this.delayer = delayer; }

    /**
     * Effective delayer for one binding target: its own override
     * (if not explicitly disabled), else the route-level default (which may
     * itself be null — no policy at all).
     *
     * @param target the binding target to resolve a delayer for
     * @return the effective {@link GuanacoDelayerConfig}, or {@code null} if none applies
     */
    public @Nullable GuanacoDelayerConfig resolveDelayerFor(BindingTarget target) {
        if (target.getDelayer() != null) {
            return target.getDelayer().resolveEnabled() ? target.getDelayer() : null;
        }
        return delayer;
    }

    /**
     * Just the URIs for a given outcome — what dispatch code (Multicast/Split/fanOut) actually needs.
     *
     * @param outcomeName the outcome's simple class name
     * @return the configured URIs for that outcome, or {@code null} if no bindings are declared for it
     */
    public @Nullable List<String> getUrisFor(String outcomeName) {
        if (bindings == null) return null;
        List<BindingTarget> targets = bindings.get(outcomeName);
        if (targets == null) return null;
        return targets.stream().map(BindingTarget::getUri).toList();
    }

    /**
     * Effective circuit breaker for one binding target: its own override
     * (if not explicitly disabled), else the route-level default (which may
     * itself be null — no policy at all).
     *
     * @param target the binding target to resolve a circuit breaker for
     * @return the effective {@link GuanacoCircuitBreakerConfig}, or {@code null} if none applies
     */
    public @Nullable GuanacoCircuitBreakerConfig resolveCircuitBreakerFor(BindingTarget target) {
        if (target.getCircuitBreaker() != null) {
            return target.getCircuitBreaker().resolveEnabled() ? target.getCircuitBreaker() : null;
        }
        return circuitBreaker;
    }

    /**
     * Effective throttler for one binding target: its own override
     * (if not explicitly disabled), else the route-level default (which may
     * itself be null — no policy at all).
     *
     * @param target the binding target to resolve a throttler for
     * @return the effective {@link GuanacoThrottlerConfig}, or {@code null} if none applies
     */
    public @Nullable GuanacoThrottlerConfig resolveThrottlerFor(BindingTarget target) {
        if (target.getThrottler() != null) {
            return target.getThrottler().resolveEnabled() ? target.getThrottler() : null;
        }
        return throttler;
    }

    /**
     * Route-level dead-letter and retry policy. {@code deadLetter} is the URI
     * messages are sent to after retries are exhausted; {@code maxRetries}
     * defaults to {@code 0} (no retry — fail straight to the dead letter).
     */
    public static class ErrorHandlerConfig {
        private @Nullable String deadLetter;
        private int maxRetries = 0;

        /** Default constructor for Jackson deserialization. */
        public ErrorHandlerConfig() { }

        /**
         * Gets the dead-letter destination URI.
         *
         * @return the dead-letter destination URI, or {@code null} if not set
         */
        public @Nullable String getDeadLetter() { return deadLetter; }

        /**
         * Sets the dead-letter destination URI.
         *
         * @param deadLetter the dead-letter destination URI
         */
        public void setDeadLetter(@Nullable String deadLetter) { this.deadLetter = deadLetter; }

        /**
         * Gets the number of retries before sending to the dead letter.
         *
         * @return the number of retries before sending to the dead letter; defaults to {@code 0}
         */
        public int getMaxRetries() { return maxRetries; }

        /**
         * Sets the number of retries before sending to the dead letter.
         *
         * @param maxRetries the number of retries before sending to the dead letter
         */
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }
}