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
 * <p>This is the closed-world pool of valid Split/Multicast destinations.
 * It is built exactly once, during {@link GuanacoContext#wireRoutes()}, and
 * never mutated or re-scanned afterward. At runtime, dispatch never performs
 * reflection, classpath scanning, or {@code Class.forName} — it only compares
 * the already-known runtime {@code Class} of an emitted outcome against this
 * frozen map, or against a processor's own sealed hierarchy.
 *
 * <p>Simple class names must be unique across the scanned package, since
 * Split/Multicast dispatch and YAML bindings both key by simple name. A
 * collision — two distinct RouteOutcome implementations sharing a simple
 * name — is a boot-time configuration error, not a silently-resolved
 * ambiguity: allowing it would make dispatch non-deterministic.
 */
public final class RouteOutcomeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RouteOutcomeRegistry.class);

    private final Map<String, Class<? extends RouteOutcome<?>>> byName;

    private RouteOutcomeRegistry(Map<String, Class<? extends RouteOutcome<?>>> byName) {
        this.byName = Collections.unmodifiableMap(byName);
    }

    /**
     * Scans {@code basePackage} once for every concrete class implementing
     * {@link RouteOutcome}, and freezes the result.
     *
     * @throws GuanacoInspectionException if two distinct classes share the
     *         same simple name within the scanned package.
     */
    public static RouteOutcomeRegistry scan(String basePackage) {
        Reflections reflections = new Reflections(basePackage);

        @SuppressWarnings({"unchecked", "rawtypes"})
        Set<Class<? extends RouteOutcome>> rawMatches = reflections.getSubTypesOf(RouteOutcome.class);

        Map<String, Class<? extends RouteOutcome<?>>> registry = new HashMap<>();
        Map<String, Class<?>> seenBy = new HashMap<>();

        for (Class<? extends RouteOutcome> candidate : rawMatches) {
            if (candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers())
                    || candidate.isAnonymousClass()
                    || candidate.isLocalClass()) {
                continue; // only concrete, named, instantiable outcome types are real destinations
            }

            String simpleName = candidate.getSimpleName();
            Class<?> previous = seenBy.put(simpleName, candidate);

            if (previous != null && !previous.equals(candidate)) {
                throw new GuanacoInspectionException(
                        "Ambiguous RouteOutcome simple name '" + simpleName + "' — both "
                        + previous.getName() + " and " + candidate.getName() + " share this name. "
                        + "Simple names must be unique within '" + basePackage + "' for Split/Multicast "
                        + "dispatch and YAML bindings to resolve deterministically. Rename one of these classes.");
            }

            @SuppressWarnings("unchecked")
            Class<? extends RouteOutcome<?>> outcomeClass = (Class<? extends RouteOutcome<?>>) candidate;
            registry.put(simpleName, outcomeClass);
        }

        log.info("RouteOutcomeRegistry: scanned '{}' — {} concrete RouteOutcome implementation(s) registered",
                basePackage, registry.size());

        return new RouteOutcomeRegistry(registry);
    }

    /** True if a concrete RouteOutcome implementation with this simple name was found at boot time. */
    public boolean contains(String simpleName) {
        return byName.containsKey(simpleName);
    }

    /** The full frozen set of registered simple names — for diagnostics/logging only. */
    public Set<String> knownNames() {
        return byName.keySet();
    }

    public int size() {
        return byName.size();
    }
}