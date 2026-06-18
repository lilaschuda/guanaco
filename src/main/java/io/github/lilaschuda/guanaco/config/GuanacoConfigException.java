package io.github.lilaschuda.guanaco.config;

/**
 * Thrown when guanaco configuration is missing, malformed, or fails validation.
 */
public class GuanacoConfigException extends RuntimeException {

    public GuanacoConfigException(String message) {
        super(message);
    }

    public GuanacoConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
