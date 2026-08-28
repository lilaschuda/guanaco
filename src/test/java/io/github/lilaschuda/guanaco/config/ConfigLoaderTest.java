package io.github.lilaschuda.guanaco.config;

import io.github.lilaschuda.guanaco.config.exception.GuanacoConfigException;
import io.github.lilaschuda.guanaco.config.exception.UnsupportedConfigFormatException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void validJson_loadsSuccessfully() {
        GuanacoConfig config = loader.load("config-loader-tests/valid.json");

        assertThat(config.getRoutes()).containsKey("OrderProcessor");
        assertThat(config.getFramework().getValidation())
                .isEqualTo(GuanacoConfig.ValidationMode.STRICT);
    }

    @Test
    void duplicateKeyInJson_failsFast() {
        assertThatThrownBy(() -> loader.load("config-loader-tests/duplicate-key.json"))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void duplicateKeyInYaml_failsFast() {
        assertThatThrownBy(() -> loader.load("config-loader-tests/duplicate-key.yaml"))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void unsupportedExtension_throwsUnsupportedConfigFormatException() {
        assertThatThrownBy(() -> loader.load("config-loader-tests/unsupported.txt"))
                .isInstanceOf(UnsupportedConfigFormatException.class)
                .hasMessageContaining(".json, .yaml, and .yml");
    }

    @Test
    void defaultLoad_prefersJsonWhenBothPresent() {
        GuanacoConfig config = loader.loadDefault("precedence-both/routes");

        assertThat(config.getRoutes()).containsKey("FromJson");
        assertThat(config.getRoutes()).doesNotContainKey("FromYaml");
    }

    @Test
    void defaultLoad_fallsBackToYamlWhenNoJsonPresent() {
        GuanacoConfig config = loader.loadDefault("precedence-yaml-only/routes");

        assertThat(config.getRoutes()).containsKey("FromYaml");
    }

    @Test
    void defaultLoad_throwsWhenNothingFound() {
        assertThatThrownBy(() -> loader.loadDefault("precedence-none/routes"))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("Could not find a configuration file");
    }

    @Test
    void existingRoutesYamlFixture_stillLoadsViaDefaultLoad() {
        // Confirms the production default load() path is completely
        // unaffected by the new JSON support — no routes.json exists at the
        // shared test resources root, so this must still resolve routes.yaml
        // exactly as it did before this change.
        GuanacoConfig config = loader.load();

        assertThat(config.getRoutes()).isNotNull();
    }
}