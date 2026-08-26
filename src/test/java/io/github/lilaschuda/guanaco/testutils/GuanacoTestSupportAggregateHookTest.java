package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.testutils.fixtures.AggregateOnlyTestProcessor;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class GuanacoTestSupportAggregateHookTest {

    private GuanacoRuntimeEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) env.shutdown();
    }

    @Test
    void withRouteAggregate_andRegisteredStrategy_mergesViaGuanacoTestSupportAlone() throws Exception {
        BindingTarget mergedTarget = new BindingTarget();
        mergedTarget.setUri("mock:merged");

        GuanacoAggregateConfig aggregate = new GuanacoAggregateConfig();
        aggregate.setCorrelationHeader("orderId");
        aggregate.setStrategyRef("concat");
        aggregate.setCompletionSize(2);

        env = new GuanacoTestSupport("io.github.lilaschuda.guanaco.testutils.fixtures")
                .registerAggregationStrategy("concat", (oldExchange, newExchange) -> {
                    if (oldExchange == null) return newExchange;
                    String oldBody = oldExchange.getIn().getBody(String.class);
                    String newBody = newExchange.getIn().getBody(String.class);
                    newExchange.getIn().setBody(oldBody + "," + newBody);
                    return newExchange;
                })
                .withRouteAggregate(aggregate)
                .route("AggregateOnlyTestProcessor", "direct:orders",
                        Map.of("ToMerged", List.of(mergedTarget)))
                .start();

        MockEndpoint merged = env.getMock("mock:merged");
        merged.expectedMessageCount(1);
        merged.expectedBodiesReceived("first,second");

        env.send("direct:orders", "first", Map.of("orderId", "order-1"));
        env.send("direct:orders", "second", Map.of("orderId", "order-1"));

        MockEndpoint.assertIsSatisfied(merged);
    }
    
    @Test
    void withRouteIdempotent_filtersDuplicateBeforeReachingProcessor() throws Exception {
        BindingTarget mergedTarget = new BindingTarget();
        mergedTarget.setUri("mock:merged");

        GuanacoIdempotentConfig idempotent = new GuanacoIdempotentConfig();
        idempotent.setMessageIdHeader("messageId");

        env = new GuanacoTestSupport("io.github.lilaschuda.guanaco.testutils.fixtures")
                .withRouteIdempotent(idempotent)
                .route("AggregateOnlyTestProcessor", "direct:orders",
                        Map.of("ToMerged", List.of(mergedTarget)))
                .start();

        MockEndpoint merged = env.getMock("mock:merged");
        merged.expectedMessageCount(1);

        env.send("direct:orders", "first", Map.of("messageId", "m1"));
        env.send("direct:orders", "duplicate", Map.of("messageId", "m1")); // same ID

        MockEndpoint.assertIsSatisfied(merged);
    }
}