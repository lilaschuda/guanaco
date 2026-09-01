package io.github.lilaschuda.guanaco.api;

import java.util.Map;

/**
 * Marks a message as participating in a Saga (long-running, compensable
 * transaction), while carrying only the genuinely per-message-dynamic
 * concerns.
 *
 * <p>Compensation and completion targets are deliberately NOT fields here.
 * Camel's {@code .saga()} resolves both to a fixed {@code Endpoint} once,
 * at route-build time (confirmed via {@code SagaReifier.createProcessor()}
 * — {@code camelContext.getEndpoint(uri)} runs once, not per exchange), so
 * they live on {@code GuanacoSagaConfig} at the route level instead, where
 * that one-per-route constraint actually belongs. A per-message field here
 * would compile, look correct, and be silently ignored by Camel at
 * runtime — every message after the first would have its compensation
 * target overridden by whatever the route's single fixed endpoint is.
 *
 * <p>{@code options} are genuinely per-message dynamic, despite Camel also
 * requiring the *set* of option keys to be fixed at boot
 * ({@code GuanacoSagaConfig#getOptionKeys()}) — only each key's *value* is
 * evaluated per exchange, which is exactly what plain, eagerly-captured
 * values here support. Deliberately {@code Map<String, Object>}, not
 * {@code Map<String, Expression>}: this is domain/outcome code, and
 * {@code org.apache.camel.Expression} — lazy, Camel-specific evaluation —
 * has no place here. Values are captured directly, inside
 * {@code process(Exchange)}, where the real data already lives.
 *
 * <p>{@code body()} delegates to {@code primary} — participating in a
 * saga is a side concern, not part of the outcome's own payload contract.
 *
 * <p>Usage:
 *   {@code return new SagaStep<>(new ToInventory(order), Map.of("orderId", order.id()));}
 *
 * @param <T> the payload type carried by the wrapped primary outcome
 */
public final class SagaStep<T> implements RouteOutcome<T> {

    private final RouteOutcome<T> primary;
    private final Map<String, Object> options;

    /**
     * Creates a Saga step with no options to snapshot.
     *
     * @param primary the outcome to route normally, exactly as if returned directly
     */
    public SagaStep(RouteOutcome<T> primary) {
        this(primary, Map.of());
    }

    /**
     * Creates a Saga step.
     *
     * @param primary the outcome to route normally, exactly as if returned directly
     * @param options exchange state to snapshot for the compensation/completion callback,
     *        keyed by names that must match {@code GuanacoSagaConfig#getOptionKeys()}
     *        on this route; a key outside that declared set is logged and ignored at runtime
     */
    public SagaStep(RouteOutcome<T> primary, Map<String, Object> options) {
        if (primary == null) {
            throw new IllegalArgumentException("SagaStep primary outcome must not be null.");
        }
        this.primary = primary;
        this.options = options != null ? Map.copyOf(options) : Map.of();
    }

    @Override
    public T body() {
        return primary.body();
    }

    /**
     * Returns the wrapped outcome that continues through normal routing.
     *
     * @return the primary {@link RouteOutcome}
     */
    public RouteOutcome<T> primary() {
        return primary;
    }

    /**
     * Returns the exchange state snapshotted for the compensation/completion callback.
     *
     * @return an unmodifiable map of option values, possibly empty
     */
    public Map<String, Object> options() {
        return options;
    }
}
