package io.github.lilaschuda.guanaco.context.exception;

/**
 * Thrown when a RouteConfig's structural shape is invalid — e.g. an
 * {@code aggregate:} block missing a required field or completion condition.
 * Always terminal, regardless of BindingValidator's ValidationMode: unlike a
 * missing/extra binding, there is no sensible "permissive" degradation for a
 * structurally incomplete configuration block.
 */
public class InvalidRouteConfigurationException extends RuntimeException {

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message description of the structural configuration defect
     */
    public InvalidRouteConfigurationException(String message) { super(message); }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message description of the structural configuration defect
     * @param cause the underlying cause, if any
     */
    public InvalidRouteConfigurationException(String message, Throwable cause) { super(message, cause); }
}