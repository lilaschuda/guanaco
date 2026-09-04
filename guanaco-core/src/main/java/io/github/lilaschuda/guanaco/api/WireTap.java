package io.github.lilaschuda.guanaco.api;

import org.jspecify.annotations.Nullable;

/**
 * Sends a copy of the message to a side-channel destination, in addition to
 * routing {@code primary} exactly as if it had been returned directly.
 *
 * <p>Modeled as an outcome wrapper — consistent with how {@link Multicast}
 * and {@link Split} are outcome types rather than binding-level config —
 * so a binding's YAML/JSON configuration never needs to know whether a
 * given outcome is tapped; that's a decision the processor makes per
 * message by choosing whether to wrap its return value.
 *
 * <p>{@code tap} is dispatched the same way any other outcome is: by its
 * own simple class name, resolved against the route's binding table. It
 * needs a routes.yaml/json entry exactly like any other {@link RouteOutcome},
 * with no special Wire Tap-specific configuration schema.
 *
 * <p>Dispatched using Camel's native, asynchronous {@code wireTap()} EIP —
 * deliberately different from {@link Multicast}/{@link Split}'s synchronous
 * fire-and-forget dispatch, since the entire point of tapping is that the
 * main flow shouldn't pay for it. A tap failure is strictly isolated to its
 * own thread and never propagates to the caller; see
 * {@code GuanacoRouteBuilder} for the unconditional SLF4J logging and
 * conditional telemetry this produces on failure.
 *
 * <p>{@code body()} delegates to {@code primary} — the tap is a side
 * effect, not part of the outcome's own payload contract, and inherits
 * whatever nullability {@code primary}'s own body() has (e.g. if
 * {@code primary} is a {@link Drop}, this is null too).
 *
 * <p>Usage:
 *   {@code return new WireTap<>(new ToInventory(order), new ToAuditLog(order));}
 *
 * @param <T> the payload type carried by the wrapped primary outcome
 */
public final class WireTap<T> implements RouteOutcome<T> {

    private final RouteOutcome<T> primary;
    private final RouteOutcome<?> tap;

    /**
     * Creates a Wire Tap outcome.
     *
     * @param primary the outcome to route normally, exactly as if returned directly
     * @param tap the outcome describing the side-channel copy's destination
     */
    public WireTap(RouteOutcome<T> primary, RouteOutcome<?> tap) {
        if (primary == null) {
            throw new IllegalArgumentException("WireTap primary outcome must not be null.");
        }
        if (tap == null) {
            throw new IllegalArgumentException("WireTap tap outcome must not be null.");
        }
        this.primary = primary;
        this.tap = tap;
    }

    @Override
    public @Nullable T body() {
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
     * Returns the outcome describing where the tapped copy is sent.
     *
     * @return the tap destination {@link RouteOutcome}
     */
    public RouteOutcome<?> tap() {
        return tap;
    }
}