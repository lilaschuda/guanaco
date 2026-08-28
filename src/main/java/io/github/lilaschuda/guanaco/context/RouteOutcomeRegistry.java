package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.context.exception.GuanacoInspectionException;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
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
 * implementation found within a single package-bounded classpath scan[cite: 38].
 */
class RouteOutcomeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RouteOutcomeRegistry.class);

    private final Map<String, Class<? extends RouteOutcome<?>>> byName;

    /**
     * Protected constructor for subclasses or internal instantiation[cite: 38].
     *
     * @param byName map of simple class names to concrete {@link RouteOutcome} classes[cite: 38]
     */
    protected RouteOutcomeRegistry(Map<String, Class<? extends RouteOutcome<?>>> byName) {
        this.byName = Collections.unmodifiableMap(new HashMap<>(byName));
    }

    /**
     * Scans {@code basePackage} once for every concrete class implementing
     * {@link RouteOutcome}, and freezes the result[cite: 38].
     *
     * @param basePackage the root package to scan[cite: 38]
     * @return a frozen registry instance[cite: 38]
     * @throws GuanacoInspectionException if two distinct classes share the same simple name[cite: 38]
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
     * Builds a name-to-class map from candidate classes, filtering out non-concrete types
     * and rejecting simple name collisions[cite: 38].
     *
     * <p>
     * Accepts a wildcard-bounded Iterable (rather than an exact
     * {@code Iterable<Class<? extends RouteOutcome<?>>>}) since this method only ever
     * reads from candidates[cite: 38].
     *
     * @param candidates candidate outcome classes to inspect[cite: 38]
     * @param sourceDescription context description for error reporting[cite: 38]
     * @return mapped simple names to concrete outcome classes[cite: 38]
     * @throws GuanacoInspectionException on a simple name collision between two distinct classes[cite: 38]
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
     * Checks if a concrete {@link RouteOutcome} with this simple name was registered[cite: 38].
     *
     * @param simpleName the simple class name to verify[cite: 38]
     * @return true if registered, false otherwise[cite: 38]
     */
    public boolean contains(String simpleName) {
        return byName.containsKey(simpleName);
    }

    /**
     * Returns the full frozen set of registered simple names[cite: 38].
     *
     * @return set of registered outcome names[cite: 38]
     */
    public Set<String> knownNames() {
        return byName.keySet();
    }

    /**
     * Returns the total count of registered outcome classes[cite: 38].
     *
     * @return registry size[cite: 38]
     */
    public int size() {
        return byName.size();
    }
}