package io.github.lilaschuda.guanaco.api;

/**
 * Explicitly discards the message — no downstream endpoint receives it.
 *
 * <p>The {@code Void} body makes the intent unambiguous: this is a deliberate
 * routing decision carrying no payload, not a missing or null result.
 *
 * <p>Usage: {@code return Drop.INSTANCE;}
 */
public final class Drop implements RouteOutcome<Void> {

    /** The single, shared {@link Drop} instance — return this to discard a message. */
    public static final Drop INSTANCE = new Drop();

    private Drop() {}

    @Override
    public Void body() { return null; }
}