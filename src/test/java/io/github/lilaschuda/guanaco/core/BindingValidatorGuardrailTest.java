package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingValidatorGuardrailTest {

    private final BindingValidator validator = new BindingValidator(ValidationMode.STRICT);

    // --- Aggregate structural validation ---

    @Test
    void noAggregateBlock_isANoOp() {
        RouteConfig config = new RouteConfig();
        assertThatCode(() -> validator.validateAggregateConfig("Test", config))
                .doesNotThrowAnyException();
    }

    @Test
    void validAggregateConfig_passes() {
        RouteConfig config = new RouteConfig();
        GuanacoAggregateConfig agg = new GuanacoAggregateConfig();
        agg.setCorrelationHeader("orderId");
        agg.setStrategyRef("orderMergeStrategy");
        agg.setCompletionSize(10);
        config.setAggregate(agg);

        assertThatCode(() -> validator.validateAggregateConfig("Test", config))
                .doesNotThrowAnyException();
    }

    @Test
    void missingCorrelationHeader_throwsInvalidRouteConfigurationException() {
        RouteConfig config = new RouteConfig();
        GuanacoAggregateConfig agg = new GuanacoAggregateConfig();
        agg.setStrategyRef("orderMergeStrategy");
        agg.setCompletionSize(10);
        config.setAggregate(agg);

        assertThatThrownBy(() -> validator.validateAggregateConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("correlationHeader");
    }

    @Test
    void blankStrategyRef_throwsInvalidRouteConfigurationException() {
        RouteConfig config = new RouteConfig();
        GuanacoAggregateConfig agg = new GuanacoAggregateConfig();
        agg.setCorrelationHeader("orderId");
        agg.setStrategyRef("   ");
        agg.setCompletionSize(10);
        config.setAggregate(agg);

        assertThatThrownBy(() -> validator.validateAggregateConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("strategyRef");
    }

    @Test
    void noCompletionCondition_throwsInvalidRouteConfigurationException() {
        RouteConfig config = new RouteConfig();
        GuanacoAggregateConfig agg = new GuanacoAggregateConfig();
        agg.setCorrelationHeader("orderId");
        agg.setStrategyRef("orderMergeStrategy");
        config.setAggregate(agg);

        assertThatThrownBy(() -> validator.validateAggregateConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("completion");
    }

    @Test
    void completionSizeOfZero_isTreatedAsNotSet() {
        RouteConfig config = new RouteConfig();
        GuanacoAggregateConfig agg = new GuanacoAggregateConfig();
        agg.setCorrelationHeader("orderId");
        agg.setStrategyRef("orderMergeStrategy");
        agg.setCompletionSize(0); // present, but not > 0 — should still fail
        config.setAggregate(agg);

        assertThatThrownBy(() -> validator.validateAggregateConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class);
    }

    // --- Script guardrail ---

    @Test
    void forbiddenSchemeInFrom_throwsForbiddenComponentException() {
        RouteConfig config = testRoute("language:groovy:some-script", Map.of());

        assertThatThrownBy(() -> validator.validate("Test", java.util.Set.of(), config, emptyRegistry()))
                .isInstanceOf(ForbiddenComponentException.class)
                .hasMessageContaining("language");
    }

    @Test
    void forbiddenSchemeInBinding_throwsForbiddenComponentException() {
        RouteConfig config = testRoute("direct:orders", Map.of("ToAudit", "js:some-script.js"));

        assertThatThrownBy(() -> validator.validate("Test", java.util.Set.of("ToAudit"), config, emptyRegistry()))
                .isInstanceOf(ForbiddenComponentException.class)
                .hasMessageContaining("js");
    }

    @Test
    void topicNameContainingForbiddenWordButNotAsScheme_passesCleanly() {
        // "python" appears mid-URI here, not as the scheme — must NOT trip
        // the guardrail. This is the exact false-positive case a substring
        // .contains() check would have wrongly flagged.
        RouteConfig config = testRoute("kafka:python:events", Map.of("ToInventory", "mock:inventory"));

        assertThatCode(() -> validator.validate("Test", java.util.Set.of("ToInventory"), config, emptyRegistry()))
                .doesNotThrowAnyException();
    }

    private RouteConfig testRoute(String from, Map<String, String> singleBindings) {
        RouteConfig config = new RouteConfig();
        config.setFrom(from);

        Map<String, Object> raw = new LinkedHashMap<>();
        singleBindings.forEach(raw::put);
        config.setBindings(raw);

        return config;
    }

    private RouteOutcomeRegistry emptyRegistry() {
        return RouteOutcomeRegistryTestSupport.of();
    }
}