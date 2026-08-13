package io.github.lilaschuda.guanaco.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes the 'bindings' block into Map&lt;String, List&lt;BindingTarget&gt;&gt;.
 * Each outcome key accepts, singly or as a list: a plain URI string, or a
 * rich object with 'uri' and an optional 'circuitBreaker'.
 */
public class BindingsDeserializer extends JsonDeserializer<Map<String, List<BindingTarget>>> {

    @Override
    public Map<String, List<BindingTarget>> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode root = mapper.readTree(p);

        Map<String, List<BindingTarget>> result = new LinkedHashMap<>();
        if (root == null || root.isNull()) {
            return result;
        }

        root.fields().forEachRemaining(entry -> result.put(entry.getKey(), parseTargets(entry.getValue(), mapper)));
        return result;
    }

    private List<BindingTarget> parseTargets(JsonNode value, ObjectMapper mapper) {
        List<BindingTarget> targets = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> targets.add(parseSingleTarget(item, mapper)));
        } else {
            targets.add(parseSingleTarget(value, mapper));
        }
        return targets;
    }

    private BindingTarget parseSingleTarget(JsonNode node, ObjectMapper mapper) {
        if (node.isTextual()) {
            BindingTarget target = new BindingTarget();
            target.setUri(node.asText());
            return target;
        }
        if (node.isObject()) {
            try {
                return mapper.treeToValue(node, BindingTarget.class);
            } catch (Exception e) {
                throw new GuanacoConfigException("Failed to parse binding target: " + node, e);
            }
        }
        throw new GuanacoConfigException(
                "Unsupported binding target shape: " + node + " — expected a string URI or an object with 'uri'.");
    }
}