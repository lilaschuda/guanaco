package io.github.lilaschuda.guanaco.example;

import org.apache.camel.Exchange;
import io.github.lilaschuda.guanaco.annotation.GuanacoRoute;
import io.github.lilaschuda.guanaco.core.RouteOutcome;
import io.github.lilaschuda.guanaco.dsl.Processor;

/**
 * Example processor demonstrating camel-guanaco's idiomatic routing model.
 *
 * The compiler enforces that every permitted OrderRoute subtype
 * is a possible return value — route topology as a type contract.
 */
@GuanacoRoute
public class OrderProcessor implements Processor<OrderRoute<?>> {

    @Override
    public OrderRoute<?> process(Exchange exchange) {
        String body = exchange.getIn().getBody(String.class);

        // Naive routing logic for prototype demonstration
        if (body != null && body.contains("suspicious")) {
            return new ToFraudCheck(body);
        }
        if (body != null && body.contains("unpaid")) {
            return new ToPayment(body);
        }
        return new ToInventory(body);
    }
}
