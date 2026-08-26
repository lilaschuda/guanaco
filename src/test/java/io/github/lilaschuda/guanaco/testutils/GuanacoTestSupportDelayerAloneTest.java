package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoDelayerConfig;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Regression test for a real bug found during the v0.9.0 review: a binding
 * with ONLY a delayer configured — no throttler, no circuit breaker — was
 * being wired incorrectly. addBranch's inline dispatch logic never handled
 * the "delayer alone" case, so .to(uri) was attached to the wrong parent
 * definition (the original branch, bypassing the delay entirely) rather
 * than to the DelayDefinition itself. Fixed by routing all cases through
 * the already-correct attachPlainTo(...) helper, which this test proves.
 */
class GuanacoTestSupportDelayerAloneTest {

    private GuanacoRuntimeEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) env.shutdown();
    }

    @Test
    void delayerAlone_noThrottlerNoCircuitBreaker_stillDeliversTheMessage() throws Exception {
        BindingTarget auditTarget = new BindingTarget();
        auditTarget.setUri("mock:audit");
        GuanacoDelayerConfig delayer = new GuanacoDelayerConfig();
        delayer.setDelayMs(50L); // no throttler, no circuitBreaker set on this target at all
        auditTarget.setDelayer(delayer);

        BindingTarget partnerTarget = new BindingTarget();
        partnerTarget.setUri("mock:partner-unused");

        env = new GuanacoTestSupport("io.github.lilaschuda.guanaco.testutils.fixtures")
                .route("DelayerTestProcessor", "direct:orders", Map.of(
                        "ToBusinessAudit", List.of(auditTarget),
                        "ToBusinessPartner", List.of(partnerTarget)))
                .start();

        MockEndpoint audit = env.getMock("mock:audit");
        audit.expectedMessageCount(1);
        audit.setResultWaitTime(2000);

        env.send("direct:orders", "audit-message");

        MockEndpoint.assertIsSatisfied(audit);
    }
}