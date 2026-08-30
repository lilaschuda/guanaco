package io.github.lilaschuda.guanaco.testutils.fixtures;

import io.github.lilaschuda.guanaco.api.GuanacoRoute;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.api.Processor;
import org.apache.camel.Exchange;

@GuanacoRoute
public class DelayerTestProcessor implements Processor<DelayerTestProcessor.OrderRoute<?>> {

    public sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToBusinessAudit, ToBusinessPartner {}
    public record ToBusinessAudit(String body)   implements OrderRoute<String> {}
    public record ToBusinessPartner(String body) implements OrderRoute<String> {}

    @Override
    public OrderRoute<?> process(Exchange exchange) {
        String body = exchange.getIn().getBody(String.class);
        return body.contains("partner") ? new ToBusinessPartner(body) : new ToBusinessAudit(body);
    }
}