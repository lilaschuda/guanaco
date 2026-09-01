package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoConfig;
import io.github.lilaschuda.guanaco.config.GuanacoSampleConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.context.exception.InvalidRouteConfigurationException;
import io.github.lilaschuda.guanaco.api.Processor;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers Sample at both levels, matching the design discussion: route-level
 * ingress (reduces a noisy source before any stateful processing) and
 * binding-level egress (the fan-out cost-control scenario -- 100% to one
 * destination, a fraction to another -- that route-level-only sampling
 * can't serve at all), plus the boot-time mutual-exclusivity guardrail.
 */
class GuanacoRouteBuilderSampleTest extends GuanacoRouteBuilderTestSupport {

    sealed interface OrderRoute<T> extends RouteOutcome<T> permits ToDatabase, ToThirdPartyAnalytics {}
    record ToDatabase(String body) implements OrderRoute<String> {}
    record ToThirdPartyAnalytics(String body) implements OrderRoute<String> {}

    @SuppressWarnings("unchecked")
    private static final Class<? extends RouteOutcome<?>> ORDER_ROUTE_CLASS =
            (Class<? extends RouteOutcome<?>>) (Class<?>) OrderRoute.class;

    @Test
    void routeLevelIngressSample_messageFrequency_onlyEveryNthMessagePasses() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToDatabase.class);
        RouteConfig config = routeConfig("direct:orders", Map.of("ToDatabase", "mock:database"));

        GuanacoSampleConfig sample = new GuanacoSampleConfig();
        sample.setMessageFrequency(2L); // 1 out of every 2 messages passes
        config.setSample(sample);

        Processor<RouteOutcome<?>> processor = exchange -> new ToDatabase(exchange.getIn().getBody(String.class));
        registerRoute(processor, ORDER_ROUTE_CLASS, config, "SampleIngressTest", registry, Map.of(), Map.of());
        context.start();

        MockEndpoint database = context.getEndpoint("mock:database", MockEndpoint.class);
        // SamplingThrottler passes the Nth message of every window (currentCount % N == 0),
        // so of 4 messages sent, exactly the 2nd and 4th pass.
        database.expectedMessageCount(2);

        var producer = context.createProducerTemplate();
        producer.sendBody("direct:orders", "1");
        producer.sendBody("direct:orders", "2");
        producer.sendBody("direct:orders", "3");
        producer.sendBody("direct:orders", "4");

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void bindingLevelEgressSample_oneTargetSampled_anotherUnaffected() throws Exception {
        RouteOutcomeRegistry registry = RouteOutcomeRegistryTestSupport.of(ToDatabase.class, ToThirdPartyAnalytics.class);
        RouteConfig config = routeConfig("direct:orders", Map.of(
                "ToDatabase", "mock:database",
                "ToThirdPartyAnalytics", "mock:analytics"));

        // Only the analytics binding is sampled -- the database binding gets
        // 100% of its traffic. This is exactly the fan-out cost-control
        // scenario from the design discussion: a route-level sampler would
        // have dropped 90% of database writes too, which is never acceptable.
        BindingTarget analyticsTarget = config.getBindings().get("ToThirdPartyAnalytics").get(0);
        GuanacoSampleConfig sample = new GuanacoSampleConfig();
        sample.setMessageFrequency(2L);
        analyticsTarget.setSample(sample);

        List<String> bodies = List.of("a", "b", "c", "d");

        Processor<RouteOutcome<?>> processor = exchange -> {
            String body = exchange.getIn().getBody(String.class);
            // Every message goes to both outcomes via the caller's own
            // fan-out logic in a real app; here we alternate to exercise
            // both branches deterministically within one route.
            return bodies.indexOf(body) % 2 == 0
                    ? new ToDatabase(body)
                    : new ToThirdPartyAnalytics(body);
        };

        registerRoute(processor, ORDER_ROUTE_CLASS, config, "SampleEgressTest", registry, Map.of(), Map.of());
        context.start();

        MockEndpoint database = context.getEndpoint("mock:database", MockEndpoint.class);
        database.expectedMessageCount(2); // "a" and "c" -- unsampled, both arrive

        MockEndpoint analytics = context.getEndpoint("mock:analytics", MockEndpoint.class);
        analytics.expectedMessageCount(1); // "b" and "d" go through the sampler -- only 1 of 2 passes

        var producer = context.createProducerTemplate();
        for (String body : bodies) {
            producer.sendBody("direct:orders", body);
        }

        MockEndpoint.assertIsSatisfied(context);
    }

    @Test
    void sampleConfig_bothFieldsSet_failsAtBoot() {
        RouteConfig config = routeConfig("direct:orders", Map.of("ToDatabase", "mock:database"));
        GuanacoSampleConfig sample = new GuanacoSampleConfig();
        sample.setMessageFrequency(2L);
        sample.setSamplePeriodMillis(1000L);
        config.setSample(sample);

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateSampleConfig("SampleBootTest", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("sets both messageFrequency and samplePeriodMillis");
    }

    @Test
    void sampleConfig_neitherFieldSet_failsAtBoot() {
        RouteConfig config = routeConfig("direct:orders", Map.of("ToDatabase", "mock:database"));
        config.setSample(new GuanacoSampleConfig()); // neither field set

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateSampleConfig("SampleBootTest", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("must set exactly one of messageFrequency or samplePeriodMillis");
    }
}
