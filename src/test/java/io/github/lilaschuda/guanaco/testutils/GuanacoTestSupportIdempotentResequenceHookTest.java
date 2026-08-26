package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Proves the withRouteIdempotent(...) and withRouteResequence(...) hooks
 * added to GuanacoTestSupport in v0.9.0 actually wire through to a real,
 * working route — not just that the fields exist and get set on RouteConfig.
 * Uses AggregateOnlyTestProcessor's single ToMerged outcome for both, since
 * neither test needs more than one outcome type.
 */
class GuanacoTestSupportIdempotentResequenceHookTest {

    private GuanacoRuntimeEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) env.shutdown();
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
        env.send("direct:orders", "duplicate", Map.of("messageId", "m1")); // same ID — must be filtered

        MockEndpoint.assertIsSatisfied(merged);
    }

    @Test
    void withRouteResequence_restoresOrderBeforeReachingProcessor() throws Exception {
        BindingTarget mergedTarget = new BindingTarget();
        mergedTarget.setUri("mock:merged");

        GuanacoResequenceConfig resequence = new GuanacoResequenceConfig();
        resequence.setSequenceHeader("seq");
        resequence.setMode(GuanacoResequenceConfig.Mode.BATCH);
        resequence.setCapacity(2); // release once both arrive

        env = new GuanacoTestSupport("io.github.lilaschuda.guanaco.testutils.fixtures")
                .withRouteResequence(resequence)
                .route("AggregateOnlyTestProcessor", "direct:orders",
                        Map.of("ToMerged", List.of(mergedTarget)))
                .start();

        MockEndpoint merged = env.getMock("mock:merged");
        merged.expectedMessageCount(2);
        // Order matters here — sent 2 then 1, expect A then B restored to
        // correct sequence order by the resequencer before either reaches
        // the processor/mock.
        merged.expectedBodiesReceived("A", "B");

        env.send("direct:orders", "B", Map.of("seq", 2));
        env.send("direct:orders", "A", Map.of("seq", 1));

        MockEndpoint.assertIsSatisfied(merged);
    }
}