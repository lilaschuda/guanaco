package io.github.lilaschuda.guanaco.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.lilaschuda.guanaco.config.exception.GuanacoConfigException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTreeMergerTest {

    private final ObjectMapper yamlMapper = strictMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = strictMapper(new JsonFactory());

    private static ObjectMapper strictMapper(JsonFactory factory) {
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        return mapper;
    }

    private ObjectNode yaml(String source) throws Exception {
        return (ObjectNode) yamlMapper.readTree(source);
    }

    private ObjectNode json(String source) throws Exception {
        return (ObjectNode) jsonMapper.readTree(source);
    }

    @Test
    void nonCollidingKeysAcrossThreeFiles_mergeCleanly() throws Exception {
        ObjectNode a = yaml("framework:\n  validation: strict\n");
        ObjectNode b = yaml("routes:\n  Orders:\n    from: direct:orders\n");
        ObjectNode c = json("{\"routes\": {\"Payments\": {\"from\": \"direct:payments\"}}}");

        ObjectNode merged = ConfigTreeMerger.merge(
                List.of(a, b, c), List.of("framework.yaml", "orders.yaml", "payments.json"));

        assertThat(merged.path("framework").path("validation").asText()).isEqualTo("strict");
        assertThat(merged.path("routes").has("Orders")).isTrue();
        assertThat(merged.path("routes").has("Payments")).isTrue();
    }

    @Test
    void differentFieldsOfTheSameRoute_mergeCleanly() throws Exception {
        // Two files each contribute different fields of the SAME route entry
        // -- the common, intended multi-file use case, not a collision.
        ObjectNode a = yaml("routes:\n  Orders:\n    from: direct:orders\n");
        ObjectNode b = yaml("routes:\n  Orders:\n    bindings:\n      ToInventory: mock:inventory\n");

        ObjectNode merged = ConfigTreeMerger.merge(List.of(a, b), List.of("a.yaml", "b.yaml"));

        assertThat(merged.path("routes").path("Orders").path("from").asText()).isEqualTo("direct:orders");
        assertThat(merged.path("routes").path("Orders").path("bindings").path("ToInventory").asText())
                .isEqualTo("mock:inventory");
    }

    @Test
    void scalarCollisionAcrossFiles_namesBothFilesAndTheExactPath() throws Exception {
        ObjectNode a = yaml("framework:\n  validation: strict\n");
        ObjectNode b = json("{\"framework\": {\"validation\": \"permissive\"}}");

        assertThatThrownBy(() -> ConfigTreeMerger.merge(List.of(a, b), List.of("first.yaml", "second.json")))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("framework.validation")
                .hasMessageContaining("first.yaml")
                .hasMessageContaining("second.json");
    }

    @Test
    void scalarCollision_namesBothFilesCorrectly_regardlessOfMergeOrder() throws Exception {
        // Same collision as above, files passed in reverse order -- proves
        // file ordering has no bearing on which files get named, matching
        // the class's own "file ordering doesn't affect the result" claim.
        ObjectNode a = yaml("framework:\n  validation: strict\n");
        ObjectNode b = json("{\"framework\": {\"validation\": \"permissive\"}}");

        assertThatThrownBy(() -> ConfigTreeMerger.merge(List.of(b, a), List.of("second.json", "first.yaml")))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("first.yaml")
                .hasMessageContaining("second.json");
    }

    @Test
    void deepCollision_onABindingDeclaredInTwoFiles_namesTheFullDottedPath() throws Exception {
        // The already-resolved "duplicate binding across files fails
        // loudly" rule, confirmed here as a natural instance of the same
        // general algorithm rather than a separately-implemented check.
        ObjectNode a = yaml("routes:\n  Orders:\n    bindings:\n      ToInventory: mock:inventory\n");
        ObjectNode b = yaml("routes:\n  Orders:\n    bindings:\n      ToInventory: mock:other-inventory\n");

        assertThatThrownBy(() -> ConfigTreeMerger.merge(List.of(a, b), List.of("orders-a.yaml", "orders-b.yaml")))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("routes.Orders.bindings.ToInventory")
                .hasMessageContaining("orders-a.yaml")
                .hasMessageContaining("orders-b.yaml");
    }

    @Test
    void arrayValueCollision_isAHardFailure_notConcatenation() throws Exception {
        // A binding's value can itself be an array (multiple targets for
        // one outcome). Two files both declaring the same array-valued key
        // must collide like any other non-object value -- concatenating
        // would reintroduce exactly the file-ordering-sensitive, implicit
        // precedence the merge algorithm otherwise deliberately avoids.
        ObjectNode a = yaml("routes:\n  Orders:\n    bindings:\n      ToInventory:\n        - mock:a\n");
        ObjectNode b = yaml("routes:\n  Orders:\n    bindings:\n      ToInventory:\n        - mock:b\n");

        assertThatThrownBy(() -> ConfigTreeMerger.merge(List.of(a, b), List.of("a.yaml", "b.yaml")))
                .isInstanceOf(GuanacoConfigException.class)
                .hasMessageContaining("routes.Orders.bindings.ToInventory");
    }

    @Test
    void emptySourceList_producesAnEmptyTree() {
        ObjectNode merged = ConfigTreeMerger.merge(List.of(), List.of());
        assertThat(merged.isEmpty()).isTrue();
    }

    @Test
    void mismatchedListSizes_throwsImmediately() {
        assertThatThrownBy(() -> ConfigTreeMerger.merge(
                List.of(yamlMapper.createObjectNode()), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}