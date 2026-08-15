package io.github.lilaschuda.guanaco.core;

/**
 * Root contract for all routing decisions in camel-guanaco.
 *
 * The type parameter T is the payload type carried by this outcome.
 * The framework calls body() uniformly — downstream processors are
 * responsible for casting to the expected type, consistent with how
 * Camel itself handles exchange bodies.
 *
 * Developers define their own sealed sub-hierarchy:
 *
 *   public sealed interface OrderRoute<T> extends RouteOutcome<T>
 *       permits ToInventory, ToPayment, ToFraudCheck {}
 *
 *   record ToInventory(String body) implements OrderRoute<String> {}
 *
 * Framework-recognized special outcomes (Drop, Multicast) are detected
 * via instanceof in GuanacoRouteBuilder — no JSON or YAML binding required for them.
 */
public interface RouteOutcome<T> {
    T body();
}
