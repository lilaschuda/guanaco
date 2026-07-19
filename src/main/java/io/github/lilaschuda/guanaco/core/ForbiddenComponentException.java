package io.github.lilaschuda.guanaco.core;

/**
 * Thrown when a route's 'from' or a binding endpoint URI resolves to a
 * scripting component scheme. Always terminal, regardless of
 * BindingValidator's ValidationMode — this is a determinism/security
 * guardrail, not a binding-completeness concern, so it is never softened by
 * PERMISSIVE or SILENT mode.
 */
public class ForbiddenComponentException extends RuntimeException {
    public ForbiddenComponentException(String message) { super(message); }
}