package io.github.lilaschuda.guanaco.config;

import io.github.lilaschuda.guanaco.config.exception.GuanacoConfigException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderDirectoryTest {

    private final ConfigLoader loader = new ConfigLoader();

    @AfterEach
    void clearSystemProperties() {
        // System properties are global, mutable state -- clearing them
        // after every test, regardless of outcome, prevents one test's
        // property from leaking into another's, since JUnit gives no
        // guarantee about test execution order or isolation within a class.
        System.clearProperty(ConfigLoader.ROUTES_DIR_PROPERTY);
        System.clearProperty(ConfigLoader.ALLOW_MIXED_FORMATS_PROPERTY);
    }

    @Test
    void noOverride_defaultsToRoutesDirectory() {
        // No "routes/" directory exists on this module's own test
        // classpath, so the DEFAULT_ROUTES_DIR fallback resolving
        // correctly is proven by the scan failing with the expected
        // "classpath*:routes/" message -- confirming which directory name
        // was actually used -- without needing to populate a directory
        // literally named "routes" and risk it colliding with some other
        // test's own classpath-scanned fixtures in the future.
        assertThatThrownBy(loader::loadFromDirectory)
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("classpath*:routes/");
    }

    @Test
    void explicitDirectoryOverload_loadsAndMergesCorrectly() {
        GuanacoConfig config = loader.loadFromDirectory("config-loader-multifile-tests/explicit");

        assertThat(config.getFramework().getValidation())
                .isEqualTo(GuanacoConfig.ValidationMode.PERMISSIVE);
        assertThat(config.getRoutes()).containsKey("OrderProcessor");
        assertThat(config.getRoutes().get("OrderProcessor").getFrom()).isEqualTo("direct:orders");
        assertThat(config.getRoutes().get("OrderProcessor").getBindings().get("ToInventory"))
                .extracting(BindingTarget::getUri)
                .containsExactly("mock:inventory");
    }

    @Test
    void routesDirProperty_overridesTheDefaultDirectory() {
        System.setProperty(ConfigLoader.ROUTES_DIR_PROPERTY, "config-loader-multifile-tests/override-target");

        GuanacoConfig config = loader.loadFromDirectory();

        assertThat(config.getFramework().getValidation()).isEqualTo(GuanacoConfig.ValidationMode.STRICT);
        assertThat(config.getRoutes()).containsKey("ShippingProcessor");
    }

    @Test
    void mixedFormatDirectory_rejectedWithoutTheProperty_evenViaExplicitOverload() {
        assertThatThrownBy(() -> loader.loadFromDirectory("config-loader-multifile-tests/mixed"))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("Mixed JSON and YAML")
                .hasMessageContaining(ConfigLoader.ALLOW_MIXED_FORMATS_PROPERTY);
    }

    @Test
    void allowMixedFormatsProperty_permitsTheSameDirectoryToLoad() {
        System.setProperty(ConfigLoader.ALLOW_MIXED_FORMATS_PROPERTY, "true");

        GuanacoConfig config = loader.loadFromDirectory("config-loader-multifile-tests/mixed");

        assertThat(config.getFramework().getValidation()).isEqualTo(GuanacoConfig.ValidationMode.STRICT);
        assertThat(config.getRoutes()).containsKey("OrderProcessor");
    }

    @Test
    void packagePrivateOverload_bypassesBothSystemProperties() {
        // Even with ROUTES_DIR_PROPERTY pointing elsewhere, the
        // package-private two-arg overload takes its directory and
        // allowMixedFormats explicitly, ignoring both properties entirely
        // -- the same isolation loadDefault(String) already gives the
        // single-file path.
        System.setProperty(ConfigLoader.ROUTES_DIR_PROPERTY, "config-loader-multifile-tests/explicit");

        GuanacoConfig config = loader.loadFromDirectory("config-loader-multifile-tests/mixed", true);

        assertThat(config.getRoutes()).containsKey("OrderProcessor");
        assertThat(config.getFramework().getValidation()).isEqualTo(GuanacoConfig.ValidationMode.STRICT);
    }
}