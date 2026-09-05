package io.github.lilaschuda.guanaco.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lilaschuda.guanaco.config.exception.GuanacoConfigException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Merges multiple parsed configuration trees (one per source file) into a
 * single combined tree, for multi-file config loading.
 *
 * <p>One recursive rule, applied uniformly at every depth: if a key exists
 * in only one of the trees being merged so far, it's simply included. If a
 * key exists in both and both values are JSON objects, the two objects are
 * merged recursively. If a key exists in both and either value is NOT an
 * object (a scalar, an array, or a type mismatch), that's a hard, boot-time
 * failure -- no precedence, no silent "last one wins", no assumption about
 * which file's value was meant to apply.
 *
 * <p>This single rule implements every one of Guanaco's multi-file merge
 * guarantees as an instance of the same behavior, not as separate special
 * cases: a {@code framework.validation} declared in two files collides (a
 * scalar reappearing); two files each declaring different fields of the
 * same {@code routes.<Name>} entry merge cleanly; and a
 * {@code bindings.<Outcome>} declared for the same outcome in two files
 * collides -- the same "duplicate binding across files fails loudly" rule
 * already established for a single file, now falling out of the general
 * algorithm rather than needing its own separate check.
 *
 * <p>File ordering is provably irrelevant to the result: the merge is
 * symmetric, so whichever of two colliding files is merged first, the same
 * collision is detected and reported (with both files correctly named,
 * regardless of which one happened to be processed first -- see the
 * provenance tracking below); for non-colliding keys, the insertion order
 * into the merged tree has no bearing on the final result either.
 */
final class ConfigTreeMerger {

    private ConfigTreeMerger() { }

    /**
     * Merges a list of parsed configuration trees, one per source file, into
     * a single combined tree.
     *
     * @param sources the parsed tree for each source file, in discovery order
     *                (order does not affect the result -- see class javadoc)
     * @param sourceNames a name for each tree in {@code sources}, same order,
     *                    used only to produce a clear error message on collision
     * @return the merged tree
     * @throws GuanacoConfigException if the same key is declared with a
     *         non-mergeable (non-object) value in more than one source
     */
    static ObjectNode merge(List<ObjectNode> sources, List<String> sourceNames) {
        if (sources.size() != sourceNames.size()) {
            throw new IllegalArgumentException("sources and sourceNames must be the same size");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        // Tracks, for every key path successfully merged so far, which
        // source file first introduced it -- so a later collision at that
        // exact path can name BOTH files involved, not just the one
        // currently being processed.
        Map<String, String> firstDeclaredIn = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            mergeInto(result, sources.get(i), "", sourceNames.get(i), firstDeclaredIn);
        }
        return result;
    }

    private static void mergeInto(
            ObjectNode target, ObjectNode source, String path, String sourceName,
            Map<String, String> firstDeclaredIn) {

        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode sourceValue = entry.getValue();
            String childPath = path.isEmpty() ? key : path + "." + key;

            if (!target.has(key)) {
                if (sourceValue.isObject()) {
                    // Recurse into a fresh, empty object node rather than a
                    // flat copy, so every nested key within this
                    // newly-introduced subtree gets its own provenance
                    // recorded too -- not just the top-level key. Without
                    // this, a LATER file colliding on a key nested inside
                    // what was, for this file, a whole subtree would have
                    // no recorded provenance to report for that nested path.
                    ObjectNode fresh = JsonNodeFactory.instance.objectNode();
                    target.set(key, fresh);
                    firstDeclaredIn.put(childPath, sourceName);
                    mergeInto(fresh, (ObjectNode) sourceValue, childPath, sourceName, firstDeclaredIn);
                } else {
                    target.set(key, sourceValue);
                    firstDeclaredIn.put(childPath, sourceName);
                }
                continue;
            }

            JsonNode targetValue = target.get(key);
            if (targetValue.isObject() && sourceValue.isObject()) {
                mergeInto((ObjectNode) targetValue, (ObjectNode) sourceValue, childPath, sourceName, firstDeclaredIn);
                continue;
            }

            String firstSource = firstDeclaredIn.getOrDefault(childPath, "another file");
            throw new GuanacoConfigException(
                    "Duplicate key '" + childPath + "' declared in more than one configuration file: "
                    + "first in " + firstSource + ", again in " + sourceName + ". Guanaco does not merge or "
                    + "apply precedence to conflicting values across files -- each key may be declared in "
                    + "exactly one file.");
        }
    }
}