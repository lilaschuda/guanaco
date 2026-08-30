package io.github.lilaschuda.guanaco.api;

import org.apache.camel.Exchange;

/**
 * Core contract for a Guanaco route processor.
 *
 * <p>Type parameter {@code R} is a sealed interface declaring all possible
 * routing outcomes for this processor. The Java compiler enforces that every
 * permitted subtype is handled — making route topology a compile-time guarantee.
 *
 * <p>Example:
 * <pre>{@code
 * public sealed interface OrderRoute permits ToInventory, ToPayment, ToDeadLetter {}
 *
 * @GuanacoRoute
 * public class OrderProcessor implements Processor<OrderRoute> {
 *     public OrderRoute process(Exchange exchange) {
 *         Order order = exchange.getIn().getBody(Order.class);
 *         if (!order.isPaid()) return new ToPayment(order);
 *         return new ToInventory(order);
 *     }
 * }
 * }</pre>
 *
 * <p>The {@link Exchange} is exposed directly in v0.1 so Camel veterans have
 * full access to headers, properties, and the full Camel API surface.
 * A higher-level Message abstraction may wrap this in future versions.
 *
 * @param <R> the sealed {@link RouteOutcome} hierarchy declaring this processor's
 *        possible routing outcomes
 */
@FunctionalInterface
public interface Processor<R> {

    /**
     * Process the incoming Camel exchange and return a routing decision.
     *
     * @param exchange the Camel exchange carrying the message
     * @return a sealed subtype instance representing the routing outcome
     * @throws Exception processors may throw; guanaco maps these to Camel error handling
     */
    R process(Exchange exchange) throws Exception;
}
