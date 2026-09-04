package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.context.exception.GuanacoInspectionException;
import io.github.lilaschuda.guanaco.api.AsyncOutcomeProcessor;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.api.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Inspects a {@link Processor} or {@link AsyncOutcomeProcessor} implementation
 * to extract its route topology by reflecting on the sealed interface type
 * parameter and its permitted subtypes.
 */
class TopologyInspector {

    private static final Logger log = LoggerFactory.getLogger(TopologyInspector.class);

    /**
     * Extracts the set of permitted subtype classes from the sealed route interface.
     *
     * @param processorClass a class implementing {@code Processor<R>} or {@code AsyncOutcomeProcessor<R>}
     * @return set of permitted outcome classes
     * @throws GuanacoInspectionException if the type cannot be inspected or {@code R} is not sealed
     */
    public Set<Class<? extends RouteOutcome<?>>> extractRouteOutcomes(Class<?> processorClass) {
        Class<? extends RouteOutcome<?>> routeInterface = extractRouteInterface(processorClass);

        Class<?>[] permitted = routeInterface.getPermittedSubclasses();
        if (permitted == null) {
            throw new GuanacoInspectionException(
                    routeInterface.getName() + " is not a sealed interface — cannot extract permitted outcomes.");
        }

        return Arrays.stream(permitted)
                .map(c -> {
                    if (!RouteOutcome.class.isAssignableFrom(c)) {
                        throw new GuanacoInspectionException(
                                "Permitted subtype " + c.getName() + " of " + routeInterface.getName()
                                + " does not implement RouteOutcome.");
                    }
                    @SuppressWarnings("unchecked")
                    Class<? extends RouteOutcome<?>> outcomeClass = (Class<? extends RouteOutcome<?>>) c;
                    return outcomeClass;
                })
                .collect(Collectors.toSet());
    }

    /**
     * Extracts the route interface type argument declared on a {@link Processor}
     * or {@link AsyncOutcomeProcessor} implementation -- whichever of the two
     * the class implements, directly or via its immediate superclass (e.g.
     * an abstract base class like {@code SuspendOutcomeProcessor} that
     * implements one on the leaf class's behalf).
     *
     * @param processorClass the target processor class to inspect
     * @return the generic type class extending {@link RouteOutcome}
     * @throws GuanacoInspectionException if the class (and its immediate superclass)
     *         does not directly specify an explicit generic outcome
     */
    public Class<? extends RouteOutcome<?>> extractRouteInterface(Class<?> processorClass) {

        boolean implementsSync = Processor.class.isAssignableFrom(processorClass);
        boolean implementsAsync = AsyncOutcomeProcessor.class.isAssignableFrom(processorClass);
        if (implementsSync && implementsAsync) {
            throw new GuanacoInspectionException(
                    processorClass.getName() + " implements both Processor and AsyncOutcomeProcessor. "
                    + "A @GuanacoRoute class must implement exactly one of the two, never both -- "
                    + "which one would silently win depends on JVM reflection ordering, which isn't guaranteed stable.");
        }

        // The common case: the class directly implements Processor<R> or
        // AsyncOutcomeProcessor<R> itself.
        for (Type genericInterface : processorClass.getGenericInterfaces()) {
            Class<? extends RouteOutcome<?>> found = tryExtractRouteInterface(genericInterface);
            if (found != null) {
                return found;
            }
        }

        // Falls back to the direct superclass -- covers an abstract base
        // class (e.g. guanaco-kotlin's SuspendOutcomeProcessor<R>) that
        // implements Processor/AsyncOutcomeProcessor for the leaf class,
        // which only extends it and binds R to a concrete type, never
        // implementing either interface directly itself.
        Type genericSuperclass = processorClass.getGenericSuperclass();
        if (genericSuperclass != null) {
            Class<? extends RouteOutcome<?>> found = tryExtractRouteInterface(genericSuperclass);
            if (found != null) {
                return found;
            }
        }

        throw new GuanacoInspectionException(
                processorClass.getName() + " does not directly implement Processor<R> or AsyncOutcomeProcessor<R>, "
                + "and its superclass does not either. Make sure the class (or its immediate superclass) "
                + "implements one of the two with an explicit type argument.");
    }

    /**
     * Checks whether {@code candidate} is a parameterized {@code Processor<R>}
     * or {@code AsyncOutcomeProcessor<R>} (or a subtype of either), and if so,
     * extracts and validates its single type argument.
     *
     * @param candidate a generic interface or generic superclass type to inspect
     * @return the validated route interface class, or {@code null} if
     *         {@code candidate} doesn't match either contract
     */
    private Class<? extends RouteOutcome<?>> tryExtractRouteInterface(Type candidate) {

        if (!(candidate instanceof ParameterizedType parameterized)) {
            return null;
        }

        Type rawType = parameterized.getRawType();
        if (!(rawType instanceof Class<?> rawClass)) {
            return null;
        }
        // A processor implements exactly one of these two contracts, never
        // both -- see AsyncOutcomeProcessor's own javadoc. Either is a
        // valid site to read the route interface's type argument from.
        if (!Processor.class.isAssignableFrom(rawClass)
                && !AsyncOutcomeProcessor.class.isAssignableFrom(rawClass)) {
            return null;
        }

        Type[] typeArgs = parameterized.getActualTypeArguments();
        if (typeArgs.length != 1) {
            return null;
        }

        Class<?> routeClass = resolveRawClass(typeArgs[0]);
        if (routeClass == null) {
            throw new IllegalArgumentException(
                    "Could not resolve a concrete class from type argument: " + typeArgs[0].getTypeName());
        }

        if (!RouteOutcome.class.isAssignableFrom(routeClass)) {
            throw new IllegalArgumentException(
                    "The provided type argument is not a subclass of RouteOutcome: " + routeClass.getName());
        }

        @SuppressWarnings("unchecked")
        Class<? extends RouteOutcome<?>> verifiedClass = (Class<? extends RouteOutcome<?>>) routeClass;
        return verifiedClass;
    }

    private Class<?> resolveRawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        if (type instanceof WildcardType wt) {
            Type[] upperBounds = wt.getUpperBounds();
            if (upperBounds.length > 0) {
                return resolveRawClass(upperBounds[0]);
            }
        }
        return null;
    }
}