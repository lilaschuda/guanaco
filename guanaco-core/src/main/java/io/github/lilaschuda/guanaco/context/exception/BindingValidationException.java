package io.github.lilaschuda.guanaco.context.exception;

/**
 * Thrown when a route's declared outcomes don't match its {@code bindings:}
 * entries in routes.yaml/json — e.g. an outcome with no corresponding binding,
 * or vice versa. Whether a given mismatch actually throws depends on the
 * configured {@link io.github.lilaschuda.guanaco.config.GuanacoConfig.ValidationMode}:
 * {@code STRICT} throws on any mismatch, {@code PERMISSIVE} warns on extras but
 * still fails on missing bindings, {@code SILENT} warns only.
 */
public class BindingValidationException extends RuntimeException {
    /**
     * Constructs a BindingValidationException with a specific message.
     *
     * @param message description of the specific binding mismatch
     */
    public BindingValidationException(String message) { super(message); }

    /**
     * Constructs a BindingValidationException with a specific message and cause.
     *
     * @param message description of the specific binding mismatch
     * @param cause the underlying cause, if any
     */
    public BindingValidationException(String message, Throwable cause) { super(message, cause); }
}