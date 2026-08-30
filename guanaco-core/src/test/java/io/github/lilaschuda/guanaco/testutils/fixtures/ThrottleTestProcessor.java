package io.github.lilaschuda.guanaco.testutils.fixtures;

import io.github.lilaschuda.guanaco.api.GuanacoRoute;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.api.Processor;
import org.apache.camel.Exchange;

@GuanacoRoute
public class ThrottleTestProcessor implements Processor<ThrottleTestProcessor.OrderRoute<?>> {

    public sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToAudit, ToPartner {}
    public record ToAudit(String body)   implements OrderRoute<String> {}
    public record ToPartner(String body) implements OrderRoute<String> {}

    @Override
    public OrderRoute<?> process(Exchange exchange) {
        String body = exchange.getIn().getBody(String.class);
        return body.contains("partner") ? new ToPartner(body) : new ToAudit(body);
    }
}