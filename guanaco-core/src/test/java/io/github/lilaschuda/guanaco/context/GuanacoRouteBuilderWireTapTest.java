package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.api.WireTap;
import io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.api.Processor;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * First-pass Wire Tap coverage. Proves the shape from the design
 * discussion: the primary outcome dispatches exactly as if it had been
 * returned directly (same binding, same downstream), a tapped copy with
 * the TAP outcome's own body (not the primary's) independently reaches
 * its own binding, and a failing tap target never affects the primary's
 * delivery -- the whole point of using Camel's native async wireTap()
 * rather than the synchronous fire-and-forget dispatch Multicast/Split use.
 */
class GuanacoRouteBuilderWireTapTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToInventory, ToAuditLog {}
    record ToInventory(String body) implements OrderRoute<String> {}
    record ToAuditLog(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    @Test
    void primaryDispatchesNormally_andTapReceivesItsOwnBody() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class, ToAuditLog.class);
        RouteConfig config = routeConfig("direct:orders",
                Map.of("ToInventory", "mock:inventory", "ToAuditLog", "mock:audit"));

        Processor<RouteOutcome<?>> processor = exchange -> {
            String body = exchange.getIn().getBody(String.class);
            return new WireTap<>(new ToInventory(body), new ToAuditLog("audit-copy-of:" + body));
        };

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "WireTapTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), null));
        context.start();

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedBodiesReceived("hello");

        MockEndpoint audit = context.getEndpoint("mock:audit", MockEndpoint.class);
        audit.expectedBodiesReceived("audit-copy-of:hello");

        context.createProducerTemplate().sendBody("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(context, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void tapFailure_neverAffectsPrimaryDelivery_andReportsViaTelemetry() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class, ToAuditLog.class);
        RouteConfig config = routeConfig("direct:orders",
                Map.of("ToInventory", "mock:inventory", "ToAuditLog", "mock:bad-audit"));

        Processor<RouteOutcome<?>> processor = exchange -> {
            String body = exchange.getIn().getBody(String.class);
            return new WireTap<>(new ToInventory(body), new ToAuditLog(body));
        };

        List<String> failures = new CopyOnWriteArrayList<>();
        GuanacoTelemetryListener listener = new GuanacoTelemetryListener() {
            @Override
            public void onOutcomeFailed(String routeId, String targetUri, Throwable cause) {
                failures.add(routeId + ":" + targetUri + ":" + cause.getClass().getSimpleName());
            }
        };

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "WireTapFailureTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), listener));
        context.start();

        MockEndpoint badAudit = context.getEndpoint("mock:bad-audit", MockEndpoint.class);
        badAudit.whenAnyExchangeReceived(exchange -> {
            throw new IllegalStateException("simulated tap failure");
        });

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedBodiesReceived("hello");

        // The call itself must not throw or block on the tap failing --
        // that's the isolation guarantee. If wiring is wrong and the tap
        // failure somehow propagates to the main flow, this line throws.
        context.createProducerTemplate().sendBody("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(context, 5, java.util.concurrent.TimeUnit.SECONDS);

        // Telemetry runs on the tap's own async thread, so poll briefly
        // rather than assert immediately. (Not using Awaitility here since
        // it isn't currently a project test dependency.)
        long deadline = System.currentTimeMillis() + 5000;
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(failures)
                .anyMatch(f -> f.startsWith("WireTapFailureTest:mock:bad-audit:IllegalStateException"));
    }
    
    @Test
    void tapFailure_doesNotReachMainDeadLetterChannel() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToInventory.class, ToAuditLog.class);
        // Main route configures a Dead Letter Channel
        RouteConfig config = routeConfigWithDeadLetter("direct:orders",
                Map.of("ToInventory", "mock:inventory", "ToAuditLog", "mock:bad-audit"), "mock:dead");

        Processor<RouteOutcome<?>> processor = exchange -> {
            String body = exchange.getIn().getBody(String.class);
            return new WireTap<>(new ToInventory(body), new ToAuditLog(body));
        };

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "WireTapDlqTest",
                new GuanacoRuntimeContext(registry, Map.of(), Map.of(), null));
        context.start();

        MockEndpoint badAudit = context.getEndpoint("mock:bad-audit", MockEndpoint.class);
        badAudit.whenAnyExchangeReceived(exchange -> {
            throw new IllegalStateException("simulated tap failure");
        });

        MockEndpoint inventory = context.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedBodiesReceived("hello");

        MockEndpoint dead = context.getEndpoint("mock:dead", MockEndpoint.class);
        dead.expectedMessageCount(0);
        
        // CRITICAL FIX: Force Camel to wait 2 seconds to ensure the async 
        // wiretap thread doesn't eventually dump a message here.
        dead.setAssertPeriod(2000); 

        context.createProducerTemplate().sendBody("direct:orders", "hello");

        // This will now wait for the assert period before passing
        MockEndpoint.assertIsSatisfied(context);
    }
}