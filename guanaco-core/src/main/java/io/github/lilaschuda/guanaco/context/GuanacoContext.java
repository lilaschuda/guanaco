package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.GuanacoDelayStrategy;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.ConfigLoader;
import io.github.lilaschuda.guanaco.config.GuanacoConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.api.GuanacoRoute;
import io.github.lilaschuda.guanaco.api.Processor;
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
import org.springframework.context.support.StaticApplicationContext;

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

    private final Map<String, AggregationStrategy> aggregationStrategies = new ConcurrentHashMap<>();
    private final Map<String, GuanacoDelayStrategy> delayStrategies = new ConcurrentHashMap<>();
    
    /**
     * Creates a new context that will scan {@code basePackage} for
     * {@link GuanacoRoute}-annotated {@link Processor} implementations once
     * {@link #wireRoutes()} is called.
     *
     * <p>Sets a lightweight, empty {@link StaticApplicationContext} by
     * default, since {@code SpringCamelContext} requires one to be present
     * before {@link #wireRoutes()}/{@code start()} regardless of whether
     * legacy XML coexistence is used. Call {@code setApplicationContext(...)}
     * with a real Spring {@code ApplicationContext} afterward (and before
     * {@link #wireRoutes()}) to give routes access to your own Spring beans.
     *
     * @param basePackage the root package to scan for route processors and
     *        {@link RouteOutcome} implementations
     */
    public GuanacoContext(String basePackage) {
        this.basePackage = basePackage;
        this.configLoader = new ConfigLoader();
        this.inspector = new TopologyInspector();
        this.setApplicationContext(new StaticApplicationContext());
    }

    /**
     * Registers a native, Java-constructed AggregationStrategy under a name
     * that {@code aggregate.strategyRef} in routes.yaml/json can reference.
     *
     * <p>Call this before {@link #wireRoutes()}; registrations made
     * afterward are not guaranteed to be visible to already-built routes.
     *
     * @param name the name referenced by {@code aggregate.strategyRef} in configuration
     * @param strategy the strategy instance to register under that name
     * @throws IllegalArgumentException if name or strategy is null/blank,
     *         or if a strategy is already registered under this name
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
     * Registers a native, Java-constructed {@link GuanacoDelayStrategy} under a name
     * that {@code delayer.delayStrategyRef} can reference.
     *
     * @param name the name referenced by {@code delayer.delayStrategyRef} in configuration
     * @param strategy the strategy instance to register under that name
     * @throws IllegalArgumentException if name or strategy is null/blank,
     *         or if a strategy is already registered under this name.
     */
    public void registerDelayStrategy(String name, GuanacoDelayStrategy strategy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("GuanacoDelayStrategy name must be provided and non-blank.");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("GuanacoDelayStrategy instance must not be null.");
        }

        GuanacoDelayStrategy previous = delayStrategies.putIfAbsent(name, strategy);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "A GuanacoDelayStrategy is already registered under name '" + name + "'. " +
                    "Registration is explicit and must be unique — choose a different name.");
        }

        log.info("Registered GuanacoDelayStrategy '{}'", name);
    }

    /**
     * Supplies the route configurations {@link #wireRoutes()} operates on. Defaults to
     * loading from routes.yaml/json. Overridable so test support code can inject
     * route configs built programmatically.
     *
     * @return the loaded configuration describing every configured route
     */
    protected GuanacoConfig loadConfig() {
        return configLoader.load();
    }

    /**
     * Scans for {@link GuanacoRoute}-annotated processors, validates their topology
     * against the configured routes, and registers the generated routes with this
     * context.
     *
     * @throws Exception if a processor cannot be instantiated, its declared topology
     *         doesn't match its configuration, or an underlying Camel route fails to build
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

        GuanacoRuntimeContext runtimeContext = new GuanacoRuntimeContext(
                outcomeRegistry, Map.copyOf(aggregationStrategies), Map.copyOf(delayStrategies));

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
            validator.validateDelayerConfig(name, routeConfig);
            validator.validateDslOnlyPolicyScope(name, routeConfig, routeInterface);
            validator.validateCircuitBreakerConfig(name, routeConfig);

            Processor<RouteOutcome<?>> instance
                    = (Processor<RouteOutcome<?>>) processorClass.getDeclaredConstructor().newInstance();

            GuanacoRouteBuilder builder = new GuanacoRouteBuilder(
                    instance, routeInterface, routeConfig, name, runtimeContext);
            this.addRoutes(builder);

            log.info("[{}] Route registered: {} → {} outcome(s)", name, routeConfig.getFrom(), outcomeNames.size());
        }

        loadLegacyXmlRoutes("META-INF/spring/camel-context.xml");
        log.info("=== camel-guanaco route wiring complete ===");
    }
    
    /**
     * Loads legacy Camel XML {@code <route>} definitions from a classpath resource.
     *
     * @param classpathResource the classpath-relative path to the XML route file
     * @throws Exception if the resource exists but its route definitions fail to parse or load
     */
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