package io.github.lilaschuda.guanaco.config;

import java.util.Map;

/**
 * Top-level POJO for the full routes.yaml file.
 *
 * routes.yaml structure:
 *
 * framework:
 *   validation: strict   # strict | permissive | silent
 *
 * routes:
 *   OrderProcessor:
 *     from: ...
 *     bindings: ...
 */
public class GuanacoConfig {

    private FrameworkConfig framework = new FrameworkConfig();
    private Map<String, RouteConfig> routes;

    public FrameworkConfig getFramework() { return framework; }
    public void setFramework(FrameworkConfig framework) { this.framework = framework; }

    public Map<String, RouteConfig> getRoutes() { return routes; }
    public void setRoutes(Map<String, RouteConfig> routes) { this.routes = routes; }

    public static class FrameworkConfig {
        private ValidationMode validation = ValidationMode.STRICT;

        public ValidationMode getValidation() { return validation; }
        public void setValidation(ValidationMode validation) { this.validation = validation; }
    }

    public enum ValidationMode {
        /** Any mismatch between sealed permits and YAML bindings is a startup failure. */
        STRICT,
        /** Extra YAML bindings are warned and ignored. Missing bindings still fail. */
        PERMISSIVE,
        /** Extra bindings ignored silently. Missing bindings warned only. */
        SILENT
    }
}
