package io.github.lilaschuda.guanaco.eip;

import io.github.lilaschuda.guanaco.core.RouteOutcome;
import java.util.List;

/**
 * Fans the message out to multiple downstream endpoints simultaneously.
 *
 * Each destination is a RouteOutcome whose class name resolves to a
 * YAML binding. The framework detects Multicast via instanceof and
 * delegates to Camel's multicast() EIP internally.
 *
 * The body() of a Multicast is the destination list itself — consistent
 * with the principle that body() always returns what was set, and the
 * caller casts appropriately.
 *
 * Usage:
 *   return new Multicast(List.of(new ToInventory(order), new ToAudit(order)));
 */
public final class Multicast implements RouteOutcome<List<? extends RouteOutcome<?>>> {

    private final List<? extends RouteOutcome<?>> destinations;

    public Multicast(List<? extends RouteOutcome<?>> destinations) {
        this.destinations = List.copyOf(destinations);
    }

    @Override
    public List<? extends RouteOutcome<?>> body() {
        return destinations;
    }

    public List<? extends RouteOutcome<?>> destinations() {
        return destinations;
    }
}