package io.github.lilaschuda.guanaco.context.exception;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when a validated, correctly-configured route still fails to translate
 * into a working Camel route graph — e.g. an unexpected Camel DSL failure while
 * wiring EIP pipelines. Distinct from {@link BindingValidationException} and
 * {@link InvalidRouteConfigurationException}, which catch configuration problems
 * before any Camel DSL is touched; this one signals a failure in that later,
 * route-building step itself.
 */
public class GuanacoRouteBuilderException extends RuntimeException {
    /**
     * Constructs a GuanacoRouteBuilderException with a specific message.
     *
     * @param message description of the route-building failure
     */
    public GuanacoRouteBuilderException(String message) { super(message); }

    /**
     * Constructs a GuanacoRouteBuilderException with a specific message and cause.
     *
     * @param message description of the route-building failure
     * @param cause the underlying Camel DSL exception
     */
    public GuanacoRouteBuilderException(String message, @Nullable Throwable cause) { super(message, cause); }
}