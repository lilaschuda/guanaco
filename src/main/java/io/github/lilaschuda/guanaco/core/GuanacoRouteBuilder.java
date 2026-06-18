package io.github.lilaschuda.guanaco.core;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.ChoiceDefinition;
import org.apache.camel.model.RouteDefinition;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import org.apache.camel.ProducerTemplate;
import io.github.lilaschuda.guanaco.eip.Drop;
import io.github.lilaschuda.guanaco.eip.Multicast;

/**
 * Generates a Camel {@link RouteBuilder} from a guanaco {@link Processor} and
 * its corresponding {@link RouteConfig}.
 *
 * <p>
 * The generated route:
 * <ol>
 * <li>Consumes from the configured 'from' endpoint</li>
 * <li>Invokes the processor, storing the routing outcome in an exchange
 * property</li>
 * <li>Uses a Camel choice() to dispatch to the correct endpoint based on
 * outcome type</li>
 * </ol>
 *
 * <p>
 * This is intentionally naive in v0.1 — it gets the concept working end to end.
 */
public class GuanacoRouteBuilder extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(GuanacoRouteBuilder.class);
    static final String OUTCOME_PROPERTY = "guanaco.outcome";

    private final Processor<? extends RouteOutcome<?>> processor;
    private final Class<? extends RouteOutcome<?>> routeInterface;
    private final RouteConfig config;
    private final String processorName;
    private ProducerTemplate producerTemplate;
    
    @SuppressWarnings("unchecked")
    public GuanacoRouteBuilder(
            Processor<? extends RouteOutcome<?>> processorInstance,
            Class<? extends RouteOutcome<?>> routeInterface,
            RouteConfig config,
            String processorName) {
        this.processor = processorInstance;
        this.routeInterface = routeInterface;
        this.config = config;
        this.processorName = processorName;
    }

    @Override
    public void configure() throws Exception {
        Map<String, String> bindings = config.getBindings();

        producerTemplate = getContext().createProducerTemplate();

        if (config.getErrorHandler() != null && config.getErrorHandler().getDeadLetter() != null) {
            errorHandler(deadLetterChannel(config.getErrorHandler().getDeadLetter())
                    .maximumRedeliveries(config.getErrorHandler().getMaxRetries())
                    .useOriginalMessage());
        }

        RouteDefinition route = from(config.getFrom())
                .routeId("guanaco-" + processorName)
                .process(exchange -> {
                    RouteOutcome<?> outcome = processor.process(exchange);

                    if (outcome == null) {
                        throw new GuanacoRouteBuilderException(
                                "[" + processorName + "] process() returned null. "
                                + "Use Drop.INSTANCE to explicitly discard a message.");
                    }

                    log.debug("[{}] Routing outcome: {}", processorName, outcome.getClass().getSimpleName());
                    exchange.setProperty(OUTCOME_PROPERTY, outcome);

                    // Drop — explicit discard, short-circuit before choice()
                    if (outcome instanceof Drop) {
                        log.debug("[{}] Drop — message explicitly discarded", processorName);
                        exchange.setRouteStop(true);
                        return;
                    }

                    // Multicast — fan out synchronously to each destination, short-circuit before choice()
                    if (outcome instanceof Multicast m) {
                        log.debug("[{}] Multicast — fanning out to {} destination(s)",
                                processorName, m.destinations().size());
                        for (RouteOutcome<?> dest : m.destinations()) {
                            String endpoint = bindings.get(dest.getClass().getSimpleName());
                            if (endpoint != null) {
                                producerTemplate.sendBody(endpoint, dest.body());
                                log.debug("[{}] Multicast → {}", processorName, endpoint);
                            } else {
                                log.warn("[{}] No binding for Multicast destination '{}' — skipping",
                                        processorName, dest.getClass().getSimpleName());
                            }
                        }
                        exchange.setRouteStop(true);
                        return;
                    }

                    // Standard single-destination outcome — proceed to choice() below
                    exchange.getIn().setBody(outcome.body());
                });

        ChoiceDefinition choice = route.choice();
        Class<?>[] permitted = routeInterface.getPermittedSubclasses();
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            String outcomeName = binding.getKey();
            String endpointUri = binding.getValue();
            Class<?> outcomeClass = (permitted == null) ? null : findPermittedSubtype(outcomeName);
            if (outcomeClass == null) {
                log.debug("[{}] Skipping choice() branch for '{}' — {} is not a sealed hierarchy "
                        + "(likely a Multicast-only route).", processorName, outcomeName, routeInterface.getName());
                continue;
            }
            choice.when(exchange -> outcomeClass.isInstance(exchange.getProperty(OUTCOME_PROPERTY)))
                    .to(endpointUri);
            log.info("[{}] Bound {} → {}", processorName, outcomeName, endpointUri);
        }

        choice.otherwise()
                .process(exchange -> {
                    Object outcome = exchange.getProperty(OUTCOME_PROPERTY);
                    String outcomeType = outcome == null ? "null" : outcome.getClass().getSimpleName();
                    log.error("[{}] Unhandled outcome type: {}. Check your YAML bindings.", processorName, outcomeType);
                })
                .stop();
    }

    /**
     * Find the permitted subtype of the route interface by simple name.
     */
    private Class<?> findPermittedSubtype(String simpleName) {
        for (Class<?> permitted : routeInterface.getPermittedSubclasses()) {
            if (permitted.getSimpleName().equals(simpleName)) {
                return permitted;
            }
        }
        throw new GuanacoInspectionException(
            "Could not find permitted subtype '" + simpleName + "' on " + routeInterface.getName()
        );
    }

    /**
     * Naive payload propagation — if the outcome has a single-arg constructor
     * carrying a body, set it on the exchange. This will be refined in future versions.
     */
    private void propagatePayload(Exchange exchange, Object outcome) {
        if (outcome == null) return;
        // Records expose components via accessor methods matching field names.
        // For v0.1 we just pass the outcome object itself as the body.
        // Users can extract what they need in the next processor.
        exchange.getIn().setBody(outcome);
    }
}
