package io.github.lilaschuda.guanaco.core;

import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, boot-time-only registry of every concrete {@link RouteOutcome}
 * implementation found within a single package-bounded classpath scan.
 *
 * <p>
 * This is the closed-world pool of valid Split/Multicast destinations.
 * Production code only ever obtains an instance via {@link #scan(String)},
 * built exactly once during {@link GuanacoContext#wireRoutes()} and never
 * mutated or re-scanned afterward. At runtime, dispatch never performs
 * reflection or classpath scanning — it only compares an already-known runtime
 * {@code Class} against this frozen map.
 *
 * <p>
 * Simple class names must be unique across the scanned candidates, since
 * Split/Multicast dispatch and YAML bindings both key by simple name. A
 * collision — two distinct RouteOutcome implementations sharing a simple name —
 * is a configuration error, not a silently-resolved ambiguity: allowing it
 * would make dispatch non-deterministic.
 *
 * <p>
 * This class is open for extension solely so that test code (see
 * {@code RouteOutcomeRegistryTestSupport} under src/test) can construct a
 * registry from an explicit, known list of classes rather than performing a
 * real classpath scan. No production code path uses anything but
 * {@link #scan(String)}.
 */
public class RouteOutcomeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RouteOutcomeRegistry.class);

    private final Map<String, Class<? extends RouteOutcome<?>>> byName;

    protected RouteOutcomeRegistry(Map<String, Class<? extends RouteOutcome<?>>> byName) {
        this.byName = Collections.unmodifiableMap(new HashMap<>(byName));
    }

    /**
     * Scans {@code basePackage} once for every concrete class implementing
     * {@link RouteOutcome}, and freezes the result. This is the only
     * construction path used by production code.
     *
     * @throws GuanacoInspectionException if two distinct classes share the same
     * simple name within the scanned package.
     */
    public static RouteOutcomeRegistry scan(String basePackage) {
        Reflections reflections = new Reflections(basePackage);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Set<Class<? extends RouteOutcome>> rawMatches = reflections.getSubTypesOf(RouteOutcome.class);

        @SuppressWarnings("unchecked")
        var candidates = rawMatches.stream()
                .map(c -> (Class<? extends RouteOutcome<?>>) (Class<?>) c)
                .toList();

        Map<String, Class<? extends RouteOutcome<?>>> registry
                = buildRegistryMap(candidates, "package scan of '" + basePackage + "'");

        log.info("RouteOutcomeRegistry: scanned '{}' — {} concrete RouteOutcome implementation(s) registered",
                basePackage, registry.size());

        return new RouteOutcomeRegistry(registry);
    }

    /**
     * Builds a name-to-class map from a set of candidate RouteOutcome classes,
     * filtering out interfaces/abstract types and rejecting simple name
     * collisions. Shared by {@link #scan(String)} and by test-only subclasses,
     * so both construction paths enforce identical integrity guarantees.
     *
     * <p>
     * Accepts a wildcard-bounded Iterable (rather than an exact
     * Iterable<Class<? extends RouteOutcome<?>>>) since this method only ever
     * reads from candidates — never adds to it. This avoids Java's generic
     * invariance rejecting callers whose collection has a slightly different,
     * but still perfectly compatible, wildcard-qualified static type.
     *
     * @throws GuanacoInspectionException on a simple name collision between two
     * distinct classes.
     */
    protected static Map<String, Class<? extends RouteOutcome<?>>> buildRegistryMap(
            Iterable<? extends Class<? extends RouteOutcome<?>>> candidates, String sourceDescription) throws GuanacoInspectionException {

        Map<String, Class<? extends RouteOutcome<?>>> registry = new HashMap<>();
        Map<String, Class<?>> seenBy = new HashMap<>();

        for (Class<? extends RouteOutcome<?>> candidate : candidates) {
            if (candidate.isInterface() || Modifier.isAbstract(candidate.getModifiers())) {
                continue;
            }

            String simpleName = candidate.getSimpleName();
            Class<?> previous = seenBy.put(simpleName, candidate);

            if (previous != null && !previous.equals(candidate)) {
                throw new GuanacoInspectionException(
                        "Ambiguous RouteOutcome simple name '" + simpleName + "' — both "
                        + previous.getName() + " and " + candidate.getName() + " share this name "
                        + "(source: " + sourceDescription + "). Simple names must be unique for "
                        + "Split/Multicast dispatch and YAML bindings to resolve deterministically. "
                        + "Rename one of these classes.");
            }

            registry.put(simpleName, candidate);
        }

        return registry;
    }

    /**
     * True if a concrete RouteOutcome implementation with this simple name was
     * found at boot time.
     */
    public boolean contains(String simpleName) {
        return byName.containsKey(simpleName);
    }

    /**
     * The full frozen set of registered simple names — for diagnostics/logging
     * only.
     */
    public Set<String> knownNames() {
        return byName.keySet();
    }

    public int size() {
        return byName.size();
    }
}
