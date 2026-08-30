package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.context.exception.GuanacoInspectionException;
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
 * Inspects a {@link Processor} implementation to extract its route topology by
 * reflecting on the sealed interface type parameter and its permitted subtypes.
 */
class TopologyInspector {

    private static final Logger log = LoggerFactory.getLogger(TopologyInspector.class);

    /**
     * Extracts the set of permitted subtype classes from the sealed route interface.
     *
     * @param processorClass a class implementing {@code Processor<R>}
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
     * Extracts the route interface type argument declared on a {@link Processor} implementation.
     *
     * @param processorClass the target processor class to inspect
     * @return the generic type class extending {@link RouteOutcome}
     * @throws GuanacoInspectionException if the class does not directly specify an explicit generic outcome
     */
    public Class<? extends RouteOutcome<?>> extractRouteInterface(Class<?> processorClass) {

        for (Type genericInterface : processorClass.getGenericInterfaces()) {

            if (!(genericInterface instanceof ParameterizedType parameterized)) {
                continue;
            }

            Type rawType = parameterized.getRawType();
            if (!(rawType instanceof Class<?> rawClass)) {
                continue;
            }
            if (!Processor.class.isAssignableFrom(rawClass)) {
                continue;
            }

            Type[] typeArgs = parameterized.getActualTypeArguments();
            if (typeArgs.length != 1) {
                continue;
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

        throw new GuanacoInspectionException(
                processorClass.getName() + " does not directly implement Processor<R>. "
                + "Make sure the class implements Processor with an explicit type argument.");
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