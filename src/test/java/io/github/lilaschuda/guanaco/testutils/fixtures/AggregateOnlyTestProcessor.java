package io.github.lilaschuda.guanaco.testutils.fixtures;

import io.github.lilaschuda.guanaco.annotation.GuanacoRoute;
import io.github.lilaschuda.guanaco.core.RouteOutcome;
import io.github.lilaschuda.guanaco.dsl.Processor;
import org.apache.camel.Exchange;

@GuanacoRoute
public class AggregateOnlyTestProcessor implements Processor<AggregateOnlyTestProcessor.OrderRoute<?>> {

    public sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToMerged {}
    public record ToMerged(String body)   implements OrderRoute<String> {}

    @Override
    public OrderRoute<?> process(Exchange exchange) {
        String body = exchange.getIn().getBody(String.class);
        return new ToMerged(body);
    }
}