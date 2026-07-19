package io.github.lilaschuda.guanaco.config;

/**
 * Thrown when a configuration resource's file extension doesn't match any
 * supported format ({@code .json}, {@code .yaml}, {@code .yml}).
 */
public class UnsupportedConfigFormatException extends GuanacoConfigException {

    public UnsupportedConfigFormatException(String message) {
        super(message);
    }
}