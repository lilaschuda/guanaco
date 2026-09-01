package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.config.GuanacoConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.context.exception.ForbiddenComponentException;
import io.github.lilaschuda.guanaco.context.exception.InvalidRouteConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers Phase 3's two boot-time guardrails around {@code controlbus:}
 * endpoints. No new outcome wrapper or config schema is needed for
 * ControlBus itself -- controlbus:route?routeId=...&action=... is a
 * completely ordinary producer endpoint under Guanaco's existing
 * binding-dispatch model. This is purely validation.
 */
class BindingValidatorControlBusTest extends GuanacoRouteBuilderTestSupport {

    @Test
    void controlBusLanguageMode_isForbidden_evenThoughSchemeIsNotOnTheScriptingList() {
        // The existing FORBIDDEN_SCHEMES check matches only the top-level
        // component scheme ("controlbus"), which isn't itself forbidden --
        // this proves the controlbus:language:... submode is caught
        // separately, not laundered through.
        RouteConfig config = routeConfig("direct:orders",
                Map.of("StopOrderRoute", "controlbus:language:simple?expression=${body}"));

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateNoForbiddenSchemes("ControlBusTest", config))
                .isInstanceOf(ForbiddenComponentException.class)
                .hasMessageContaining("controlbus:language:");
    }

    @Test
    void controlBusUnknownMode_isRejected() {
        RouteConfig config = routeConfig("direct:orders",
                Map.of("StopOrderRoute", "controlbus:routes?routeId=orderRoute&action=stop")); // typo: "routes"

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateNoForbiddenSchemes("ControlBusTest", config))
                .isInstanceOf(ForbiddenComponentException.class)
                .hasMessageContaining("Unsupported controlbus mode");
    }

    @Test
    void controlBusRoute_missingAction_failsAtBoot() {
        RouteConfig config = routeConfig("direct:orders",
                Map.of("StopOrderRoute", "controlbus:route?routeId=orderRoute"));

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateNoForbiddenSchemes("ControlBusTest", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("missing a required 'action' parameter");
    }

    @Test
    void controlBusRoute_invalidAction_failsAtBoot() {
        RouteConfig config = routeConfig("direct:orders",
                Map.of("StopOrderRoute", "controlbus:route?routeId=orderRoute&action=terminate"));

        BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

        assertThatThrownBy(() -> validator.validateNoForbiddenSchemes("ControlBusTest", config))
                .isInstanceOf(InvalidRouteConfigurationException.class)
                .hasMessageContaining("not a recognized route lifecycle action");
    }

    @Test
    void controlBusRoute_validAction_passesForEachAllowedAction() {
        for (String action : new String[] {"start", "stop", "suspend", "resume", "status"}) {
            RouteConfig config = routeConfig("direct:orders",
                    Map.of("StopOrderRoute", "controlbus:route?routeId=orderRoute&action=" + action));

            BindingValidator validator = new BindingValidator(GuanacoConfig.ValidationMode.STRICT);

            assertThatCode(() -> validator.validateNoForbiddenSchemes("ControlBusTest", config))
                    .as("action=" + action + " should be accepted")
                    .doesNotThrowAnyException();
        }
    }
}
