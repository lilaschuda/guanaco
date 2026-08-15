package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.ConfigLoader;
import io.github.lilaschuda.guanaco.config.GuanacoConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.annotation.GuanacoRoute;
import io.github.lilaschuda.guanaco.dsl.Processor;
import org.apache.camel.AggregationStrategy;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.io.InputStream;
import java.util.stream.Collectors;
import org.apache.camel.spi.Resource;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.springframework.context.ApplicationContext;

/**
 * Main entry point for camel-guanaco.
 *
 * <p>Usage:
 * <pre>{@code
 * GuanacoContext ctx = new GuanacoContext("org.myapp");
 * ctx.registerAggregationStrategy("orderMergeStrategy", new OrderMergeStrategy()); // if using Aggregate
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

    // Populated via registerAggregationStrategy() before wireRoutes() is
    // called. A frozen snapshot is handed to each GuanacoRouteBuilder at
    // wireRoutes() time — resolution then happens exactly once per route,
    // at route-graph-construction time, never per-message.
    private final Map<String, AggregationStrategy> aggregationStrategies = new ConcurrentHashMap<>();

    public GuanacoContext(String basePackage) {
        this.basePackage = basePackage;
        this.configLoader = new ConfigLoader();
        this.inspector = new TopologyInspector();
    }
    
    //Overload with custom ApplicationContext for tests support
    public GuanacoContext(String basePackage, ApplicationContext applicationContext){
        this.basePackage = basePackage;
        this.configLoader = new ConfigLoader();
        this.inspector = new TopologyInspector();
        this.setApplicationContext(applicationContext);
    }

    /**
     * Registers a native, Java-constructed AggregationStrategy under a name
     * that {@code aggregate.strategyRef} in routes.yaml/json can reference.
     * No Spring bean lookup, no reflection — a plain, explicit, closed-world
     * name-to-instance registration.
     *
     * <p>Call this before {@link #wireRoutes()}; registrations made
     * afterward are not guaranteed to be visible to already-built routes.
     *
     * @throws IllegalArgumentException if name or strategy is null/blank,
     *         or if a strategy is already registered under this name —
     *         registration is explicit and deterministic, never silently
     *         overwritten.
     */
    public void registerAggregationStrategy(String name, AggregationStrategy strategy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("AggregationStrategy name must be provided and non-blank.");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("AggregationStrategy instance must not be null.");
        }

        AggregationStrategy previous = aggregationStrategies.putIfAbsent(name, strategy);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "An AggregationStrategy is already registered under name '" + name + "'. " +
                    "Registration is explicit and must be unique — choose a different name.");
        }

        log.info("Registered AggregationStrategy '{}'", name);
    }

    /**
     * Supplies the route configurations wireRoutes() operates on. Defaults to
     * loading from routes.yaml/json via ConfigLoader. Overridable so test
     * support code (see GuanacoTestSupport) can inject route configs built
     * programmatically, bypassing a physical config file entirely.
     */
    protected GuanacoConfig loadConfig() {
        return configLoader.load();
    }

    /**
     * Scans for @GuanacoRoute processors, validates their topology against
     * routes.yaml, and registers the generated routes with this context.
     *
     * Call this BEFORE start().
     */
    public void wireRoutes() throws Exception {
        log.info("=== camel-guanaco wiring routes ===");

        GuanacoConfig config = loadConfig();
        BindingValidator validator = new BindingValidator(config.getFramework().getValidation());
        Map<String, RouteConfig> routeConfigs = config.getRoutes();

        if (routeConfigs == null || routeConfigs.isEmpty()) {
            log.warn("No routes defined — nothing to wire");
            return;
        }

        RouteOutcomeRegistry outcomeRegistry = RouteOutcomeRegistry.scan(basePackage);

        // Frozen snapshot handed to every builder — see field javadoc.
        Map<String, AggregationStrategy> strategiesSnapshot = Map.copyOf(aggregationStrategies);

        Reflections reflections = new Reflections(basePackage);
        Set<Class<?>> rawProcessorClasses = reflections.getTypesAnnotatedWith(GuanacoRoute.class);

        @SuppressWarnings("unchecked")
        Set<Class<? extends Processor<? extends RouteOutcome<?>>>> processorClasses = rawProcessorClasses.stream()
                .filter(Processor.class::isAssignableFrom)
                .map(clazz -> (Class<? extends Processor<? extends RouteOutcome<?>>>) clazz)
                .collect(Collectors.toSet());

        log.info("Found {} @GuanacoRoute processor(s) in package '{}'", processorClasses.size(), basePackage);

        for (Class<? extends Processor<? extends RouteOutcome<?>>> processorClass : processorClasses) {
            String name = resolveProcessorName(processorClass);
            RouteConfig routeConfig = routeConfigs.get(name);

            if (routeConfig == null) {
                log.warn("No routes config entry found for processor '{}' — skipping. "
                        + "Add a '{}:' block under 'routes:' to activate it.", name, name);
                continue;
            }

            Set<Class<? extends RouteOutcome<?>>> outcomeClasses = inspector.extractRouteOutcomes(processorClass);
            Class<? extends RouteOutcome<?>> routeInterface = inspector.extractRouteInterface(processorClass);

            Set<String> outcomeNames = outcomeClasses.stream()
                    .map(Class::getSimpleName)
                    .collect(Collectors.toSet());

            validator.validate(name, outcomeNames, routeConfig, outcomeRegistry);
            validator.validateAggregateConfig(name, routeConfig);
            validator.validateIdempotentConfig(name, routeConfig);
            validator.validateResequenceConfig(name, routeConfig);
            validator.validateThrottlerConfig(name, routeConfig);
            validator.validateDslOnlyPolicyScope(name, routeConfig, routeInterface);

            Processor<RouteOutcome<?>> instance
                    = (Processor<RouteOutcome<?>>) processorClass.getDeclaredConstructor().newInstance();

            GuanacoRouteBuilder builder = new GuanacoRouteBuilder(
                    instance, routeInterface, routeConfig, name, outcomeRegistry, strategiesSnapshot);
            this.addRoutes(builder);

            log.info("[{}] Route registered: {} → {} outcome(s)", name, routeConfig.getFrom(), outcomeNames.size());
        }

        loadLegacyXmlRoutes("META-INF/spring/camel-context.xml");
        log.info("=== camel-guanaco route wiring complete ===");
    }

    public void loadLegacyXmlRoutes(String classpathResource) throws Exception {
        Resource resource = ResourceHelper.resolveMandatoryResource(this, "classpath:" + classpathResource);

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