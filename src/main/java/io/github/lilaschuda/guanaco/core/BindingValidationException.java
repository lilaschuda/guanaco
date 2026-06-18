package io.github.lilaschuda.guanaco.core;

public class BindingValidationException extends RuntimeException {
    public BindingValidationException(String message) { super(message); }
    public BindingValidationException(String message, Throwable cause) { super(message, cause); }
}
