package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode;
import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
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

    // --- Resequence structural validation ---
    @Test
    void noResequenceBlock_isANoOp() {
        RouteConfig config = new RouteConfig();
        assertThatCode(() -> validator.validateResequenceConfig("Test", config))
                .doesNotThrowAnyException();
    }

    @Test
    void validStreamConfig_passes() {
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.STREAM);
        config.setResequence(reseq);

        assertThatCode(() -> validator.validateResequenceConfig("Test", config))
                .doesNotThrowAnyException();
    }

    @Test
    void validBatchConfig_passes() {
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.BATCH);
        reseq.setCapacity(100);
        config.setResequence(reseq);

        assertThatCode(() -> validator.validateResequenceConfig("Test", config))
                .doesNotThrowAnyException();
    }

    @Test
    void missingSequenceHeader_throwsInvalidRouteConfigurationException() {
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setMode(GuanacoResequenceConfig.Mode.STREAM);
        config.setResequence(reseq);

        assertThatThrownBy(() -> validator.validateResequenceConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("sequenceHeader");
    }

    @Test
    void blankSequenceHeader_throwsInvalidRouteConfigurationException() {
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("   ");
        reseq.setMode(GuanacoResequenceConfig.Mode.STREAM);
        config.setResequence(reseq);

        assertThatThrownBy(() -> validator.validateResequenceConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("sequenceHeader");
    }

    @Test
    void missingMode_throwsInvalidRouteConfigurationException() {
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        config.setResequence(reseq);

        assertThatThrownBy(() -> validator.validateResequenceConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("mode");
    }

    @Test
    void batchModeWithNoCompletionCondition_throwsInvalidRouteConfigurationException() {
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.BATCH);
        config.setResequence(reseq);

        assertThatThrownBy(() -> validator.validateResequenceConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("completion");
    }

    @Test
    void batchModeWithZeroCapacityAndNoTimeout_throwsInvalidRouteConfigurationException() {
        // capacity present but not > 0, and no timeout either — still no valid
        // completion condition, same as leaving both unset.
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.BATCH);
        reseq.setCapacity(0);
        config.setResequence(reseq);

        assertThatThrownBy(() -> validator.validateResequenceConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("completion");
    }

    @Test
    void batchModeWithRejectOldSet_throwsInvalidRouteConfigurationException() {
        // rejectOld only has meaning in STREAM mode — its presence alongside
        // BATCH almost certainly signals a typo in 'mode', so this is rejected
        // outright rather than silently ignored.
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.BATCH);
        reseq.setCapacity(100);
        reseq.setRejectOld(true);
        config.setResequence(reseq);

        assertThatThrownBy(() -> validator.validateResequenceConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("rejectOld");
    }

    @Test
    void streamModeWithNegativeCapacity_throwsInvalidRouteConfigurationException() {
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.STREAM);
        reseq.setCapacity(-5);
        config.setResequence(reseq);

        assertThatThrownBy(() -> validator.validateResequenceConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void streamModeWithZeroTimeout_throwsInvalidRouteConfigurationException() {
        RouteConfig config = new RouteConfig();
        GuanacoResequenceConfig reseq = new GuanacoResequenceConfig();
        reseq.setSequenceHeader("seq");
        reseq.setMode(GuanacoResequenceConfig.Mode.STREAM);
        reseq.setTimeoutMs(0L);
        config.setResequence(reseq);

        assertThatThrownBy(() -> validator.validateResequenceConfig("Test", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("timeoutMs");
    }
}
