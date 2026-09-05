package io.github.lilaschuda.guanaco.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.lilaschuda.guanaco.config.exception.GuanacoConfigException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigDirectoryScannerTest {

    private final ObjectMapper yamlMapper = strictMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = strictMapper(new JsonFactory());
    private final ConfigDirectoryScanner scanner = new ConfigDirectoryScanner(yamlMapper, jsonMapper);

    private static ObjectMapper strictMapper(JsonFactory factory) {
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        return mapper;
    }

    @Test
    void cleanSingleFormatDirectory_scansAndMergesCorrectly() {
        ConfigDirectoryScanner.ScanResult result = scanner.scan("dir-scanner-tests/clean", false);

        assertThat(result.trees()).hasSize(2);

        ObjectNode merged = ConfigTreeMerger.merge(result.trees(), result.names());
        assertThat(merged.path("framework").path("validation").asText()).isEqualTo("strict");
        assertThat(merged.path("routes").path("Orders").path("from").asText()).isEqualTo("direct:orders");
    }

    @Test
    void mixedFormatDirectory_rejectedByDefault() {
        assertThatThrownBy(() -> scanner.scan("dir-scanner-tests/mixed", false))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("Mixed JSON and YAML")
                .hasMessageContaining("guanaco.config.allowMixedFormats");
    }

    @Test
    void mixedFormatDirectory_allowedExplicitly_appliesPerLogicalNamePrecedence() {
        ConfigDirectoryScanner.ScanResult result = scanner.scan("dir-scanner-tests/mixed", true);

        // Exactly 2 logical files, not 3: payments.json wins over
        // payments.yaml for the "payments" logical name, while the
        // unrelated orders.yaml (no JSON counterpart) still loads normally.
        assertThat(result.trees()).hasSize(2);

        ObjectNode merged = ConfigTreeMerger.merge(result.trees(), result.names());
        assertThat(merged.path("routes").path("Payments").path("from").asText())
                .as("JSON should win over YAML for the same logical name")
                .isEqualTo("direct:payments-json");
        assertThat(merged.path("routes").path("Orders").path("from").asText())
                .as("a YAML-only logical name with no JSON counterpart should be unaffected")
                .isEqualTo("direct:orders");
    }

    @Test
    void directoryWithNoRecognizedConfigFiles_throwsAClearError() {
        assertThatThrownBy(() -> scanner.scan("dir-scanner-tests/empty", false))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("No configuration files found")
                .hasMessageContaining("dir-scanner-tests/empty");
    }

    @Test
    void sameLogicalNameAndFormat_fromTwoDifferentSources_isRejected() {
        // Simulates the same directory name being contributed by two
        // different classpath locations (e.g. two separate jars), which
        // isn't practical to set up with real jar files in a plain unit
        // test -- verified against real jars separately during design.
        // indexByLogicalName is package-private specifically so this test
        // can exercise it directly against hand-built Resource stubs.
        Resource fromJarA = fakeYamlResource(
                "shipping.yaml", "jar:file:/fake-a.jar!/routes/shipping.yaml", "routes: {}");
        Resource fromJarB = fakeYamlResource(
                "shipping.yaml", "jar:file:/fake-b.jar!/routes/shipping.yaml", "routes: {}");

        assertThatThrownBy(() -> scanner.indexByLogicalName(List.of(fromJarA, fromJarB)))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("shipping")
                .hasMessageContaining("fake-a.jar")
                .hasMessageContaining("fake-b.jar");
    }

    private static Resource fakeYamlResource(String filename, String url, String content) {
        return new AbstractResource() {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public String getDescription() {
                return url;
            }

            @Override
            public URL getURL() throws java.io.IOException {
                return URI.create(url).toURL();
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(content.getBytes());
            }
        };
    }
}