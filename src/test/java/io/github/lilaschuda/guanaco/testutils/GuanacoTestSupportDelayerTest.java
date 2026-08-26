package io.github.lilaschuda.guanaco.testutils;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoDelayerConfig;
import io.github.lilaschuda.guanaco.core.GuanacoDelayStrategy;
import io.github.lilaschuda.guanaco.core.GuanacoRouteBuilderException;
import org.apache.camel.Exchange;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the delayer hierarchy resolves correctly at runtime and that
 * delayStrategyRef genuinely invokes compiled Java against the real
 * Exchange, rather than being a hardcoded fixed value. Timing itself
 * (asyncDelayed, actual wall-clock pause) is Camel's own, already-tested
 * behavior — these tests focus on wiring correctness: does the right
 * strategy get invoked, does the message still arrive, does an unresolved
 * strategyRef fail loudly at build time.
 */
class GuanacoTestSupportDelayerTest {

    private static final String BASE_PACKAGE = "io.github.lilaschuda.guanaco.testutils.fixtures";

    private GuanacoRuntimeEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) env.shutdown();
    }

    @Test
    void fixedDelayMs_stillDeliversTheMessage() throws Exception {
        BindingTarget auditTarget = new BindingTarget();
        auditTarget.setUri("mock:businessAudit");
        GuanacoDelayerConfig delayer = new GuanacoDelayerConfig();
        delayer.setDelayMs(50L); // short, since the test waits on real delivery
        auditTarget.setDelayer(delayer);

        BindingTarget partnerTarget = new BindingTarget();
        partnerTarget.setUri("mock:partner-unused");

        env = new GuanacoTestSupport(BASE_PACKAGE)
                .route("DelayerTestProcessor", "direct:businessOrders", Map.of(
                        "ToBusinessAudit", List.of(auditTarget),
                        "ToBusinessPartner", List.of(partnerTarget)))
                .start();

        MockEndpoint audit = env.getMock("mock:businessAudit");
        audit.expectedMessageCount(1);
        audit.setResultWaitTime(2000);

        env.send("direct:businessOrders", "audit-message");

        MockEndpoint.assertIsSatisfied(audit);
    }

    @Test
    void delayStrategyRef_invokesCompiledStrategyWithTheRealExchange() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger(0);
        AtomicReference<String> observedHeader = new AtomicReference<>();

        GuanacoDelayStrategy strategy = (Exchange exchange) -> {
            invocationCount.incrementAndGet();
            observedHeader.set(exchange.getIn().getHeader("priority", String.class));
            return 10L; // short — proves invocation, doesn't need to prove exact timing
        };

        BindingTarget partnerTarget = new BindingTarget();
        partnerTarget.setUri("mock:businessPartner");
        GuanacoDelayerConfig delayer = new GuanacoDelayerConfig();
        delayer.setDelayStrategyRef("testBackoff");
        partnerTarget.setDelayer(delayer);

        BindingTarget auditTarget = new BindingTarget();
        auditTarget.setUri("mock:businessAudit-unused");

        var support = new GuanacoTestSupport(BASE_PACKAGE)
                .route("DelayerTestProcessor", "direct:businessOrders", Map.of(
                        "ToBusinessAudit", List.of(auditTarget),
                        "ToBusinessPartner", List.of(partnerTarget)));

        // NOTE: assumes GuanacoTestSupport exposes a way to register a
        // GuanacoDelayStrategy before start(), mirroring
        // GuanacoContext.registerDelayStrategy(...). If GuanacoTestSupport
        // doesn't yet have this hook, it needs one — same gap pattern as
        // withRouteThrottler/withRouteCircuitBreaker needing to be added
        // when the Throttler tests first surfaced the missing API.
        env = support.registerDelayStrategy("testBackoff", strategy).start();

        MockEndpoint partner = env.getMock("mock:businessPartner");
        partner.expectedMessageCount(1);
        partner.setResultWaitTime(2000);

        env.send("direct:businessOrders", "partner-message", Map.of("priority", "high"));

        MockEndpoint.assertIsSatisfied(partner);
        assertThat(invocationCount.get()).isEqualTo(1);
        assertThat(observedHeader.get()).isEqualTo("high");
    }

    @Test
    void unresolvedDelayStrategyRef_throwsAtStartTime() {
        BindingTarget partnerTarget = new BindingTarget();
        partnerTarget.setUri("mock:businessPartner");
        GuanacoDelayerConfig delayer = new GuanacoDelayerConfig();
        delayer.setDelayStrategyRef("doesNotExist");
        partnerTarget.setDelayer(delayer);

        BindingTarget auditTarget = new BindingTarget();
        auditTarget.setUri("mock:businessAudit-unused");

        var support = new GuanacoTestSupport(BASE_PACKAGE)
                .route("DelayerTestProcessor", "direct:businessOrders", Map.of(
                        "ToBusinessAudit", List.of(auditTarget),
                        "ToBusinessPartner", List.of(partnerTarget)));

        // No strategy registered at all — "doesNotExist" cannot resolve.
        // wireRoutes() calls addRoutes(builder), which runs configure()
        // synchronously — same point Aggregate's equivalent test asserts on.
        assertThatThrownBy(support::start)
                .isInstanceOf(GuanacoRouteBuilderException.class)
                .hasMessageContaining("doesNotExist");
    }

    @Test
    void enabledFalse_optsOutOfInheritedDelayer() throws Exception {
        GuanacoDelayerConfig routeDelayer = new GuanacoDelayerConfig();
        routeDelayer.setDelayMs(5000L); // deliberately long — if this DID
                                          // apply, the test would time out
                                          // waiting for it, catching a
                                          // regression clearly rather than
                                          // silently passing either way.

        BindingTarget auditTarget = new BindingTarget();
        auditTarget.setUri("mock:businessAudit");
        GuanacoDelayerConfig optOut = new GuanacoDelayerConfig();
        optOut.setEnabled(false);
        auditTarget.setDelayer(optOut);

        BindingTarget partnerTarget = new BindingTarget();
        partnerTarget.setUri("mock:businessPartner-unused");

        // NOTE: assumes withRouteDelayer(...) exists on GuanacoTestSupport,
        // mirroring withRouteThrottler/withRouteCircuitBreaker.
        env = new GuanacoTestSupport(BASE_PACKAGE)
                .withRouteDelayer(routeDelayer)
                .route("DelayerTestProcessor", "direct:businessOrders", Map.of(
                        "ToBusinessAudit", List.of(auditTarget),
                        "ToBusinessPartner", List.of(partnerTarget)))
                .start();

        MockEndpoint audit = env.getMock("mock:businessAudit");
        audit.expectedMessageCount(1);
        audit.setResultWaitTime(1000); // short — must arrive quickly since
                                         // the 5000ms route default should
                                         // NOT apply to this binding

        env.send("direct:businessOrders", "audit-message");

        MockEndpoint.assertIsSatisfied(audit);
    }
}