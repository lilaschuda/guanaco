package io.github.lilaschuda.guanaco.config.exception;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when guanaco configuration is missing, malformed, or fails validation.
 */
public class GuanacoConfigException extends RuntimeException {

    /**
     * Constructs a GuanacoConfigException with a specific message.
     *
     * @param message description of the configuration problem
     */
    public GuanacoConfigException(String message) {
        super(message);
    }

    /**
     * Constructs a GuanacoConfigException with a specific message and cause.
     *
     * @param message description of the configuration problem
     * @param cause the underlying cause, if any
     */
    public GuanacoConfigException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}