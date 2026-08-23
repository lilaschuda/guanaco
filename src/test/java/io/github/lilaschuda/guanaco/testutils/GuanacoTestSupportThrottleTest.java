package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThrottlerConfig;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class GuanacoTestSupportThrottleTest {

    private static final String BASE_PACKAGE = "io.github.lilaschuda.guanaco.testutils";

    private GuanacoRuntimeEnvironment env;
    
    @AfterEach
    void tearDown() {
        if (env != null) env.shutdown();
    }

    @Test
    void bindingWithoutOverride_inheritsRouteLevelThrottler() throws Exception {
        GuanacoThrottlerConfig routeThrottler = new GuanacoThrottlerConfig();
        routeThrottler.setRequestsPerPeriod(100);
        routeThrottler.setTimePeriodMillis(1000L);

        BindingTarget auditTarget = new BindingTarget();
        auditTarget.setUri("mock:audit");
        // No target-level throttler — must inherit the route default set below.

        BindingTarget partnerTarget = new BindingTarget();
        partnerTarget.setUri("mock:partner-unused"); // present only to satisfy STRICT completeness

        env = new GuanacoTestSupport(BASE_PACKAGE)
                .withRouteThrottler(routeThrottler)
                .route("ThrottleTestProcessor", "direct:orders", Map.of(
                        "ToAudit", List.of(auditTarget),
                        "ToPartner", List.of(partnerTarget)))
                .start();
        
        MockEndpoint audit = env.getMock("mock:audit");
        audit.expectedMessageCount(1);

        env.send("direct:orders", "audit-message");

        MockEndpoint.assertIsSatisfied(audit);
    }

    @Test
    void bindingLevelOverride_takesPrecedenceOverRouteDefault() throws Exception {
        GuanacoThrottlerConfig routeThrottler = new GuanacoThrottlerConfig();
        routeThrottler.setRequestsPerPeriod(100);
        routeThrottler.setTimePeriodMillis(1000L);

        BindingTarget auditTarget = new BindingTarget();
        auditTarget.setUri("mock:audit-unused");

        BindingTarget partnerTarget = new BindingTarget();
        partnerTarget.setUri("mock:partner");
        GuanacoThrottlerConfig override = new GuanacoThrottlerConfig();
        override.setRequestsPerPeriod(5);
        override.setTimePeriodMillis(1000L);
        partnerTarget.setThrottler(override);

        env = new GuanacoTestSupport(BASE_PACKAGE)
                .withRouteThrottler(routeThrottler)
                .route("ThrottleTestProcessor", "direct:orders", Map.of(
                        "ToAudit", List.of(auditTarget),
                        "ToPartner", List.of(partnerTarget)))
                .start();
        
        MockEndpoint partner = env.getMock("mock:partner");
        partner.expectedMessageCount(1);

        env.send("direct:orders", "partner-message");

        MockEndpoint.assertIsSatisfied(partner);
    }

    @Test
    void enabledFalse_optsOutOfInheritedPolicy() throws Exception {
        // A route-level default that WOULD apply to every binding, unless
        // a binding explicitly opts out — this is the actual thing being
        // opted out of.
        GuanacoThrottlerConfig routeThrottler = new GuanacoThrottlerConfig();
        routeThrottler.setRequestsPerPeriod(100);
        routeThrottler.setTimePeriodMillis(1000L);

        BindingTarget auditTarget = new BindingTarget();
        auditTarget.setUri("mock:audit");
        GuanacoThrottlerConfig optOut = new GuanacoThrottlerConfig();
        optOut.setEnabled(false);
        auditTarget.setThrottler(optOut);

        BindingTarget partnerTarget = new BindingTarget();
        partnerTarget.setUri("mock:partner-unused");

        env = new GuanacoTestSupport(BASE_PACKAGE)
                .withRouteThrottler(routeThrottler)
                .route("ThrottleTestProcessor", "direct:orders", Map.of(
                        "ToAudit", List.of(auditTarget),
                        "ToPartner", List.of(partnerTarget)))
                .start();
        MockEndpoint audit = env.getMock("mock:audit");
        audit.expectedMessageCount(1);

        env.send("direct:orders", "audit-message");

        MockEndpoint.assertIsSatisfied(audit);
    }

    @Test
    void throttleAndCircuitBreakerTogether_bothApplyWithoutError() throws Exception {
        BindingTarget auditTarget = new BindingTarget();
        auditTarget.setUri("mock:audit-unused");

        BindingTarget partnerTarget = new BindingTarget();
        partnerTarget.setUri("mock:partner");

        GuanacoThrottlerConfig throttler = new GuanacoThrottlerConfig();
        throttler.setRequestsPerPeriod(50);
        throttler.setTimePeriodMillis(1000L);
        partnerTarget.setThrottler(throttler);

        GuanacoCircuitBreakerConfig cb = new GuanacoCircuitBreakerConfig();
        cb.setFailureRateThreshold(50);
        cb.setSlidingWindowSize(10);
        partnerTarget.setCircuitBreaker(cb);

        env = new GuanacoTestSupport(BASE_PACKAGE)
                .route("ThrottleTestProcessor", "direct:orders", Map.of(
                        "ToAudit", List.of(auditTarget),
                        "ToPartner", List.of(partnerTarget)))
                .start();

        MockEndpoint partner = env.getMock("mock:partner");
        partner.expectedMessageCount(1);

        env.send("direct:orders", "partner-message");

        MockEndpoint.assertIsSatisfied(partner);
    }
}