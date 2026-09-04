package io.github.lilaschuda.guanaco.example.async;

import io.github.lilaschuda.guanaco.api.AsyncOutcomeProcessor;
import io.github.lilaschuda.guanaco.api.GuanacoRoute;
import io.github.lilaschuda.guanaco.api.OutcomeCallback;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import org.apache.camel.Exchange;

/**
 * Fixture for GuanacoContextAsyncScanTest, deliberately isolated in its own
 * package rather than living alongside GuanacoRouteBuilder*Test's own
 * nested fixtures. Those other tests scope their outcome registry
 * explicitly via RouteOutcomeRegistryTestSupport.of(...), so their
 * commonly-named fixtures (ToInventory, etc.) never collide with each
 * other. This test performs a REAL classpath scan (GuanacoTestSupport +
 * GuanacoContext.wireRoutes()'s actual Reflections-based discovery), which
 * would pick up every RouteOutcome implementor across the whole scanned
 * package -- including everyone else's same-named test fixtures -- if it
 * scanned io.github.lilaschuda.guanaco.context directly. Isolating this in
 * its own package matches GuanacoContextTest's own established precedent.
 */
public class AsyncScanFixtures {

    public sealed interface AsyncOrderRoute<T> extends RouteOutcome<T> permits AsyncToInventory {}

    public record AsyncToInventory(String body) implements AsyncOrderRoute<String> {}

    @GuanacoRoute
    public static class RealAsyncProcessor implements AsyncOutcomeProcessor<AsyncOrderRoute<?>> {
        @Override
        public void process(Exchange exchange, OutcomeCallback<AsyncOrderRoute<?>> callback) {
            String body = exchange.getIn().getBody(String.class);
            callback.onOutcome(new AsyncToInventory(body));
        }
    }
}