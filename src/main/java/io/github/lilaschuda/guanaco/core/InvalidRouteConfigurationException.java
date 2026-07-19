package io.github.lilaschuda.guanaco.core;

/**
 * Thrown when a RouteConfig's structural shape is invalid — e.g. an
 * {@code aggregate:} block missing a required field or completion condition.
 * Always terminal, regardless of BindingValidator's ValidationMode: unlike a
 * missing/extra binding, there is no sensible "permissive" degradation for a
 * structurally incomplete configuration block.
 */
public class InvalidRouteConfigurationException extends RuntimeException {
    public InvalidRouteConfigurationException(String message) { super(message); }
    public InvalidRouteConfigurationException(String message, Throwable cause) { super(message, cause); }
}