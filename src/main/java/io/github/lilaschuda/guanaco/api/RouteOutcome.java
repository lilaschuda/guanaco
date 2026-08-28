package io.github.lilaschuda.guanaco.api;

/**
 * Root contract for all routing decisions in camel-guanaco.
 *
 * <p>The type parameter {@code T} is the payload type carried by this outcome.
 * The framework calls {@code body()} uniformly — downstream processors are
 * responsible for casting to the expected type, consistent with how
 * Camel itself handles exchange bodies.
 *
 * <p>Developers define their own sealed sub-hierarchy:
 *
 * <pre>{@code
 * public sealed interface OrderRoute<T> extends RouteOutcome<T>
 *     permits ToInventory, ToPayment, ToFraudCheck {}
 *
 * record ToInventory(String body) implements OrderRoute<String> {}
 * }</pre>
 *
 * <p>Framework-recognized special outcomes (Drop, Multicast) are detected
 * via instanceof in GuanacoRouteBuilder — no JSON or YAML binding required for them.
 *
 * @param <T> the payload type carried by this outcome
 */
public interface RouteOutcome<T> {

    /**
     * Returns the message payload carried by this routing outcome.
     *
     * @return the payload instance, or {@code null} if no payload is attached
     */
    T body();
}