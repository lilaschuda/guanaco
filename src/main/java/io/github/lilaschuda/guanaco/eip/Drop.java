package io.github.lilaschuda.guanaco.eip;

import io.github.lilaschuda.guanaco.core.RouteOutcome;

/**
 * Explicitly discards the message — no downstream endpoint receives it.
 *
 * The Void body makes the intent unambiguous: this is a deliberate
 * routing decision carrying no payload, not a missing or null result.
 *
 * Usage:
 *   return Drop.INSTANCE;
 */
public final class Drop implements RouteOutcome<Void> {

    public static final Drop INSTANCE = new Drop();

    private Drop() {}

    @Override
    public Void body() { return null; }
}