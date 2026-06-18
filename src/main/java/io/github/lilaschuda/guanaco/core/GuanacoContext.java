package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.ConfigLoader;
import io.github.lilaschuda.guanaco.config.GuanacoConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import org.apache.camel.impl.DefaultCamelContext;
import io.github.lilaschuda.guanaco.annotation.GuanacoRoute;
import io.github.lilaschuda.guanaco.dsl.Processor;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import java.io.InputStream;
import java.util.stream.Collectors;
import org.apache.camel.spi.Resource;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;

/**
 * Main entry point for camel-guanaco.
 *
 * <p>
 * Usage:
 * <pre>{@code
 * GuanacoContext ctx = new GuanacoContext("org.myapp");
 * ctx.wireRoutes(); // scans, validates, registers routes — call BEFORE start()
 * ctx.start();      // real Camel startup — routes, consumers, etc.
 * // ...
 * ctx.stop();       // real Camel shutdown
 * }</pre>
 */
public class GuanacoContext extends SpringCamelContext {

    private static final Logger log = LoggerFactory.getLogger(GuanacoContext.class);

    private final String basePackage;
    private final ConfigLoader configLoader;
    private final TopologyInspector inspector;

    public GuanacoContext(String basePackage) {
        this.basePackage = basePackage;
        this.configLoader = new ConfigLoader();
        this.inspector = new TopologyInspector();
    }

    /**
     * Scans for @GuanacoRoute processors, validates their topology against
     * routes.yaml, and registers the generated routes with this context.
     *
     * Call this BEFORE start().
     */
    public void wireRoutes() throws Exception {
        log.info("=== camel-guanaco v0.1 wiring routes ===");

        GuanacoConfig config = configLoader.load();
        BindingValidator validator = new BindingValidator(config.getFramework().getValidation());
        Map<String, RouteConfig> routeConfigs = config.getRoutes();

        if (routeConfigs == null || routeConfigs.isEmpty()) {
            log.warn("No routes defined in routes.yaml — nothing to wire");
            return;
        }

        Reflections reflections = new Reflections(basePackage);
        Set<Class<?>> rawProcessorClasses = reflections.getTypesAnnotatedWith(GuanacoRoute.class);
        
        @SuppressWarnings("unchecked")
        Set<Class<? extends Processor<? extends RouteOutcome<?>>>> processorClasses = rawProcessorClasses.stream()
            //.filter(RouteOutcome.class::isAssignableFrom)
            @SuppressWarnings("unchecked")
            .map(clazz -> (Class<? extends Processor<? extends RouteOutcome<?>>>)clazz)
            .collect(Collectors.toSet());
        
        log.info("Found {} @GuanacoRoute processor(s) in package '{}'", processorClasses.size(), basePackage);

        for (Class<? extends Processor<? extends RouteOutcome<?>>> processorClass : processorClasses) {
            if (!Processor.class.isAssignableFrom(processorClass)) {
                log.warn("Class {} is annotated @GuanacoRoute but does not implement Processor<R> — skipping",
                        processorClass.getName());
                continue;
            }

            String name = resolveProcessorName(processorClass);
            RouteConfig routeConfig = routeConfigs.get(name);

            if (routeConfig == null) {
                log.warn("No routes.yaml entry found for processor '{}' — skipping. "
                        + "Add a '{}:' block under 'routes:' to activate it.", name, name);
                continue;
            }

            Set<Class<? extends RouteOutcome<?>>> outcomeClasses = inspector.extractRouteOutcomes(processorClass);
            Class<? extends RouteOutcome<?>> routeInterface = inspector.extractRouteInterface(processorClass);

            Set<String> outcomeNames = outcomeClasses.stream()
                    .map(Class::getSimpleName)
                    .collect(Collectors.toSet());

            validator.validate(name, outcomeNames, routeConfig);

            Processor<RouteOutcome<?>> instance = (Processor<RouteOutcome<?>>)processorClass.getDeclaredConstructor().newInstance();

            GuanacoRouteBuilder builder = new GuanacoRouteBuilder(instance, routeInterface, routeConfig, name);
            this.addRoutes(builder);

            log.info("[{}] Route registered: {} → {} outcome(s)", name, routeConfig.getFrom(), outcomeNames.size());
        }

        loadLegacyXmlRoutes("META-INF/spring/camel-context.xml");
        log.info("=== camel-guanaco route wiring complete ===");
    }

    /**
     * Legacy compatibility: loads <route> definitions from a Spring-flavored
     * camel-context.xml without requiring a Spring ApplicationContext.
     *
     * Only <route> elements are honored — beans, data formats, property
     * placeholders, and other Spring-wired concerns are ignored.
     *
     * Call this BEFORE start(). Missing files are logged and skipped, not fatal
     * — this is meant as an opt-in migration aid.
     */
    public void loadLegacyXmlRoutes(String classpathResource) throws Exception {
        // 1. Resolve the resource from the classpath using Camel's native helper
        Resource resource = ResourceHelper.resolveMandatoryResource(this, "classpath:" + classpathResource);

        // 2. Check if the resource actually contains bytes to read safely
        try (InputStream is = resource.getInputStream()) {
            if (is == null) {
                log.info("No legacy XML routes found at '{}' — skipping", classpathResource);
                return;
            }
        } catch (Exception e) {
            log.info("No legacy XML routes found at '{}' — skipping ({})", classpathResource, e.getMessage());
            return;
        }

        log.info("Parsing <route> definitions out of legacy footprint: '{}'", classpathResource);

        // 3. Let Camel's native routes loader isolate and bind the <route> elements.
        // This automatically skips <beans>, property placeholders, etc.
        PluginHelper.getRoutesLoader(this).loadRoutes(resource);

        log.info("Successfully isolated and loaded legacy routes from '{}'", classpathResource);
    }

    private String resolveProcessorName(Class<?> processorClass) {
        GuanacoRoute annotation = processorClass.getAnnotation(GuanacoRoute.class);
        if (annotation != null && !annotation.name().isEmpty()) {
            return annotation.name();
        }
        return processorClass.getSimpleName();
    }
}
