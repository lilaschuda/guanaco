package io.github.lilaschuda.guanaco.config;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Top-level POJO for the full routes.json or routes.yaml file.
 *
 * <p>routes.yaml structure:
 * <pre>{@code
 * framework:
 *   validation: strict   # strict | permissive | silent
 *
 * routes:
 *   OrderProcessor:
 *     from: ...
 *     bindings: ...
 * }</pre>
 */
public class GuanacoConfig {

    private FrameworkConfig framework = new FrameworkConfig();
    private @Nullable Map<String, RouteConfig> routes;

    /** Default constructor, used by Jackson when deserializing the root configuration. */
    public GuanacoConfig() { }

    /**
     * Gets the framework configuration settings.
     *
     * @return the {@code framework:} block settings for this configuration; never {@code null} --
     *         an explicit {@code framework: null} in YAML/JSON, or a direct
     *         {@code setFramework(null)} call, falls back to a fresh default rather than storing null
     */
    public FrameworkConfig getFramework() { return framework; }

    /**
     * Sets the framework configuration settings.
     *
     * @param framework the {@code framework:} block settings for this configuration,
     *                   or {@code null} to reset to a fresh default
     */
    public void setFramework(@Nullable FrameworkConfig framework) {
        this.framework = framework != null ? framework : new FrameworkConfig();
    }

    /**
     * Gets the map of configured routes.
     *
     * @return the configured routes, keyed by route/processor name, or {@code null} if none configured
     */
    public @Nullable Map<String, RouteConfig> getRoutes() { return routes; }

    /**
     * Sets the map of configured routes.
     *
     * @param routes the configured routes, keyed by route/processor name
     */
    public void setRoutes(@Nullable Map<String, RouteConfig> routes) { this.routes = routes; }

    /** Top-level {@code framework:} settings; currently just the {@link ValidationMode}. */
    public static class FrameworkConfig {
        private ValidationMode validation = ValidationMode.STRICT;

        /** Default constructor for framework configuration settings. */
        public FrameworkConfig() { }

        /**
         * Gets the binding validation strictness mode.
         *
         * @return the configured binding validation strictness; never {@code null} -- defaults to
         *         {@link ValidationMode#STRICT}, and an explicit {@code validation: null} or
         *         {@code setValidation(null)} call falls back to that same default rather than storing null
         */
        public ValidationMode getValidation() { return validation; }

        /**
         * Sets the binding validation strictness mode.
         *
         * @param validation the binding validation strictness to apply, or {@code null} to reset to the default
         */
        public void setValidation(@Nullable ValidationMode validation) {
            this.validation = validation != null ? validation : ValidationMode.STRICT;
        }
    }

    /**
     * Controls how strictly a route's declared outcomes are checked against
     * its {@code bindings:} entries in routes.yaml/json.
     */
    public enum ValidationMode {
        /** Any mismatch between sealed permits and YAML bindings is a startup failure. */
        STRICT,
        /** Extra YAML bindings are warned and ignored. Missing bindings still fail. */
        PERMISSIVE,
        /** Extra bindings ignored silently. Missing bindings warned only. */
        SILENT
    }
}