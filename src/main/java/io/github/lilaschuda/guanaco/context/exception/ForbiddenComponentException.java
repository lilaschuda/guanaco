package io.github.lilaschuda.guanaco.context.exception;

/**
 * Thrown when a route's 'from' or a binding endpoint URI resolves to a
 * scripting component scheme. Always terminal, regardless of
 * BindingValidator's ValidationMode — this is a determinism/security
 * guardrail, not a binding-completeness concern, so it is never softened by
 * PERMISSIVE or SILENT mode.
 */
public class ForbiddenComponentException extends RuntimeException {
    /**
     * Constructs a ForbiddenComponentException with a specific message.
     *
     * @param message description of the forbidden component reference
     */
    public ForbiddenComponentException(String message) { super(message); }
}