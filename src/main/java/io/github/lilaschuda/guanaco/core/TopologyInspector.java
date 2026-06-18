package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.dsl.Processor;
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
 *
 * <p>
 * Given:
 * <pre>
 *   public class OrderProcessor implements Processor&lt;OrderRoute&gt; { ... }
 *   public sealed interface OrderRoute permits ToInventory, ToPayment { ... }
 * </pre>
 *
 * TopologyInspector extracts: { "ToInventory", "ToPayment" } These become the
 * expected keys in the YAML bindings.
 */
public class TopologyInspector {

    private static final Logger log = LoggerFactory.getLogger(TopologyInspector.class);

    /**
     * Extract the set of permitted subtype simple names from the sealed route
     * interface declared as the type parameter of the given processor class.
     *
     * @param processorClass a class implementing Processor&lt;R&gt;
     * @return simple names of all permitted subtypes of R
     * @throws GuanacoInspectionException if the type cannot be inspected or R
     * is not sealed
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
     * Extract the actual Class of R from a class implementing Processor.
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

    /**
     * Resolves the raw Class behind a type argument that may itself be
     * parameterized (e.g. OrderRoute<?> from Processor<OrderRoute<?>>), a plain
     * Class (e.g. String from Processor<String>), or a wildcard.
     */
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
