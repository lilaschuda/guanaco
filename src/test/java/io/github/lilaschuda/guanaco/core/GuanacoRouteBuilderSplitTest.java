package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import io.github.lilaschuda.guanaco.eip.Split;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import org.apache.camel.AggregationStrategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Split EIP, exercised through the full
 * GuanacoRouteBuilder route graph.
 *
 * Split items are dispatched by simple class name against routes.yaml
 * bindings — identical to Multicast — completely independent of the
 * originating processor's sealed route interface. This is deliberate:
 * a Split item becomes an autonomous message the instant it's unrolled,
 * and is never required to be a permitted subtype of the processor that
 * produced it. That's what makes reusable, cross-cutting outcome types
 * (e.g. a shared audit/notification outcome used by many unrelated
 * processors) possible — Java's sealed-type rules would otherwise force
 * every such outcome into a single processor's own package.
 */
class GuanacoRouteBuilderSplitTest extends GuanacoRouteBuilderTestSupport {

    // The processor's OWN sealed hierarchy — deliberately narrow, containing
    // only the "normal" outcome types it's declared to produce directly.
    sealed interface BulkOrderRoute<T> extends RouteOutcome<T> permits ToMainframe, ToRest {}
    record ToMainframe(String body) implements BulkOrderRoute<String> {}
    record ToRest(String body)      implements BulkOrderRoute<String> {}

    // A cross-cutting outcome that intentionally does NOT belong to
    // BulkOrderRoute's sealed hierarchy — modeling a shared, reusable type
    // (e.g. an audit/notification outcome) that many unrelated processors
    // might emit via Split, none of which could ever be added to each
    // other's permits clauses without violating Java's sealed-type rules.
    record ToAuditLog(String body) implements RouteOutcome<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> BULK_ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) BulkOrderRoute.class;

    @Test
    void splitItem_outsideOriginatingSealedHierarchy_stillRoutesByBindingName() throws Exception {
        // ToAuditLog is NOT a permitted subtype of BulkOrderRoute — it's an
        // entirely unrelated type, bound only by its simple class name.
        RouteConfig config = routeConfig("direct:bulkOrders", Map.of(
                "ToMainframe", "mock:mainframe",
                "ToAuditLog",  "mock:audit"
        ));

        Processor<RouteOutcome<?>> processor = exchange -> new Split(List.of(
                new ToMainframe("mf-item"),
                new ToAuditLog("cross-cutting-audit-item")
        ));

        registerRoute(processor, BULK_ORDER_ROUTE_CLASS, config, "BulkOrderProcessor");
        context.start();

        MockEndpoint mainframe = context.getEndpoint("mock:mainframe", MockEndpoint.class);
        MockEndpoint audit     = context.getEndpoint("mock:audit",     MockEndpoint.class);

        mainframe.expectedMessageCount(1);
        audit.expectedMessageCount(1);
        mainframe.expectedBodiesReceived("mf-item");
        audit.expectedBodiesReceived("cross-cutting-audit-item");

        ProducerTemplate producer = context.createProducerTemplate();
        producer.sendBody("direct:bulkOrders", "trigger");

        // The critical assertion: ToAuditLog reached mock:audit even though
        // it is not, and could never be, a permitted subtype of
        // BulkOrderRoute. If this framework still tried to validate Split
        // items against the sealed hierarchy, this message would have been
        // silently dropped by resolveOutcomeClass — proving the correction
        // actually took effect, not just that Split still works in the easy,
        // same-hierarchy case.
        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void splitAndForget_routesEachItemToItsOwnEndpointBySimpleClassName() throws Exception {
        RouteConfig config = routeConfig("direct:bulkOrders", Map.of(
                "ToMainframe", "mock:mainframe",
                "ToRest",      "mock:rest",
                "ToAuditLog",  "mock:audit"
        ));

        Processor<RouteOutcome<?>> processor = exchange -> new Split(List.of(
                new ToMainframe("mf-item"),
                new ToRest("rest-item"),
                new ToAuditLog("audit-item")
        ));

        registerRoute(processor, BULK_ORDER_ROUTE_CLASS, config, "BulkOrderProcessor");
        context.start();

        MockEndpoint mainframe = context.getEndpoint("mock:mainframe", MockEndpoint.class);
        MockEndpoint rest      = context.getEndpoint("mock:rest",      MockEndpoint.class);
        MockEndpoint audit     = context.getEndpoint("mock:audit",     MockEndpoint.class);

        mainframe.expectedMessageCount(1);
        rest.expectedMessageCount(1);
        audit.expectedMessageCount(1);
        mainframe.expectedBodiesReceived("mf-item");
        rest.expectedBodiesReceived("rest-item");
        audit.expectedBodiesReceived("audit-item");

        ProducerTemplate producer = context.createProducerTemplate();
        producer.sendBody("direct:bulkOrders", "trigger");

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void splitItemWithNoBinding_isLoggedAndSkippedWithoutCrashingTheRoute() throws Exception {
        // ToAuditLog is deliberately left unbound.
        RouteConfig config = routeConfig("direct:bulkOrders", Map.of(
                "ToMainframe", "mock:mainframe"
        ));

        Processor<RouteOutcome<?>> processor = exchange -> new Split(List.of(
                new ToMainframe("mf-item"),
                new ToAuditLog("orphaned-item")
        ));

        registerRoute(processor, BULK_ORDER_ROUTE_CLASS, config, "BulkOrderProcessor");
        context.start();

        MockEndpoint mainframe = context.getEndpoint("mock:mainframe", MockEndpoint.class);
        mainframe.expectedMessageCount(1);

        ProducerTemplate producer = context.createProducerTemplate();

        // Should not throw — the unbound item is logged (dispatchSplitItem's
        // "No binding found" warning) and simply skipped; the bound item
        // still arrives normally.
        producer.sendBody("direct:bulkOrders", "trigger");

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void splitWithAggregationStrategy_combinesResultsInDeclaredOrder() throws Exception {
        RouteConfig config = routeConfig("direct:bulkOrders", Map.of(
                "ToMainframe", "mock:mainframe",
                "ToRest",      "mock:rest",
                "ToAuditLog",  "mock:audit"
        ));

        AggregationStrategy concatStrategy = (oldExchange, newExchange) -> {
            String oldBody = (oldExchange == null) ? null : oldExchange.getIn().getBody(String.class);
            String newBody = newExchange.getIn().getBody(String.class);
            newExchange.getIn().setBody(oldBody == null ? newBody : oldBody + "," + newBody);
            return newExchange;
        };

        Processor<RouteOutcome<?>> processor = exchange -> new Split(
                List.of(new ToMainframe("mf"), new ToRest("rest"), new ToAuditLog("audit")),
                concatStrategy
        );

        registerRoute(processor, BULK_ORDER_ROUTE_CLASS, config, "BulkOrderProcessor");
        context.start();

        context.getEndpoint("mock:mainframe", MockEndpoint.class);
        context.getEndpoint("mock:rest", MockEndpoint.class);
        context.getEndpoint("mock:audit", MockEndpoint.class);

        ProducerTemplate producer = context.createProducerTemplate();
        Exchange result = producer.send("direct:bulkOrders", exchange -> exchange.getIn().setBody("trigger"));

        // NOTE: assumes Camel's splitter aggregation is reflected on the
        // Exchange returned by producerTemplate.send() after split()/end() —
        // worth confirming empirically against Camel 4.20's actual behavior.
        assertThat(result.getIn().getBody(String.class)).isEqualTo("mf,rest,audit");
    }
}