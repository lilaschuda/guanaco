package io.github.lilaschuda.guanaco.config.exception;

/**
 * Thrown when a configuration resource's file extension doesn't match any
 * supported format ({@code .json}, {@code .yaml}, {@code .yml}).
 */
public class UnsupportedConfigFormatException extends GuanacoConfigException {

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message description of the unrecognized file extension
     */
    public UnsupportedConfigFormatException(String message) {
        super(message);
    }
}