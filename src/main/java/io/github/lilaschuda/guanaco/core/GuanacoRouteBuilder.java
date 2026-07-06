package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import io.github.lilaschuda.guanaco.eip.Drop;
import io.github.lilaschuda.guanaco.eip.Multicast;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.ChoiceDefinition;
import org.apache.camel.model.RouteDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Generates a Camel {@link RouteBuilder} from a Guanaco {@link Processor} and
 * its corresponding {@link RouteConfig}.
 *
 * <p>The generated route:
 * <ol>
 *   <li>Consumes from the configured 'from' endpoint</li>
 *   <li>Invokes the processor, storing the routing outcome in an exchange property</li>
 *   <li>Short-circuits for {@link Drop} (discard) and {@link Multicast} (fan-out)</li>
 *   <li>Otherwise dispatches via a Camel {@code choice()} based on outcome type</li>
 * </ol>
 *
 * <p><b>Multicast delivery semantics:</b> fan-out is best-effort / fire-and-forget.
 * A failed send to one destination does not stop delivery to the remaining
 * destinations. Failed sends are routed to the configured dead letter endpoint
 * ({@code errorHandler.deadLetter} in routes.yaml) if one is set; if none is
 * configured, the failure is logged loudly and the message is lost. This
 * assumes destinations are typically durable queues with their own delivery
 * guarantees — Guanaco does not take on responsibility for cross-destination
 * consistency. A future version may expose a per-route "strict" mode that
 * stops the fan-out and propagates the first failure immediately instead.
 */
public class GuanacoRouteBuilder extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(GuanacoRouteBuilder.class);
    static final String OUTCOME_PROPERTY = "guanaco.outcome";

    private final Processor<? extends RouteOutcome<?>> processor;
    private final Class<? extends RouteOutcome<?>> routeInterface;
    private final RouteConfig config;
    private final String processorName;

    // Created once in configure(); Camel manages its lifecycle alongside the CamelContext.
    private ProducerTemplate producerTemplate;

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
        producerTemplate = getContext().createProducerTemplate();
        configureErrorHandler();

        RouteDefinition route = from(config.getFrom())
                .routeId("guanaco-" + processorName)
                .process(this::dispatchOutcome);

        buildChoiceTable(route.choice());
    }

    private void configureErrorHandler() {
        if (config.getErrorHandler() != null && config.getErrorHandler().getDeadLetter() != null) {
            errorHandler(deadLetterChannel(config.getErrorHandler().getDeadLetter())
                    .maximumRedeliveries(config.getErrorHandler().getMaxRetries())
                    .useOriginalMessage());
        }
    }

    /**
     * Invokes the processor and handles the routing outcome. Drop and Multicast
     * are resolved here and short-circuit the route; standard outcomes fall
     * through to the choice() table built in {@link #buildChoiceTable}.
     */
    private void dispatchOutcome(Exchange exchange) throws Exception {
        RouteOutcome<?> outcome = processor.process(exchange);

        if (outcome == null) {
            throw new GuanacoRouteBuilderException(
                    "[" + processorName + "] process() returned null. " +
                    "Use Drop.INSTANCE to explicitly discard a message.");
        }

        log.debug("[{}] Routing outcome: {}", processorName, outcome.getClass().getSimpleName());
        exchange.setProperty(OUTCOME_PROPERTY, outcome);

        if (outcome instanceof Drop) {
            log.debug("[{}] Drop — message explicitly discarded", processorName);
            exchange.setRouteStop(true);
            return;
        }

        if (outcome instanceof Multicast multicast) {
            fanOut(exchange, multicast);
            return;
        }

        exchange.getIn().setBody(outcome.body());
    }

    /**
     * Sends each Multicast destination's payload to its bound endpoint(s), on a
     * fresh exchange per send so bodies stay isolated per destination.
     *
     * <p>Best-effort: a failed send is routed to the dead letter endpoint (if
     * configured) and logged, but does not stop the fan-out from continuing
     * to remaining destinations.
     */
    private void fanOut(Exchange exchange, Multicast multicast) {
        Map<String, List<String>> bindings = config.getBindings();
        log.debug("[{}] Multicast — fanning out to {} destination(s)",
                processorName, multicast.destinations().size());

        int failureCount = 0;

        for (RouteOutcome<?> destination : multicast.destinations()) {
            String outcomeName = destination.getClass().getSimpleName();
            List<String> endpoints = bindings.get(outcomeName);

            if (endpoints == null || endpoints.isEmpty()) {
                log.warn("[{}] No binding found for Multicast destination '{}' — skipping",
                        processorName, outcomeName);
                continue;
            }

            for (String endpoint : endpoints) {
                if (!sendToEndpoint(destination, endpoint)) {
                    failureCount++;
                }
            }
        }

        if (failureCount > 0) {
            log.warn("[{}] Multicast completed with {} failed send(s) — see errors above.",
                    processorName, failureCount);
        }

        exchange.setRouteStop(true);
    }

    /**
     * Sends a single destination's payload to a single endpoint. Failures are
     * logged and swallowed here — by design, one failed destination does not
     * stop the rest of the fan-out or the parent route.
     *
     * <p>On failure, the destination's payload is routed to the configured dead
     * letter endpoint ({@code errorHandler.deadLetter} in routes.yaml), if one
     * is set. If no dead letter is configured, the failure is logged loudly —
     * the message is lost in that case, which is worth surfacing clearly
     * rather than silently.
     *
     * @return true if the send succeeded, false if it failed (regardless of
     *         whether it was successfully forwarded to a dead letter).
     */
    private boolean sendToEndpoint(RouteOutcome<?> destination, String endpoint) {
        log.debug("[{}] Multicast → {}", processorName, endpoint);

        Exchange child = producerTemplate.getCamelContext().getEndpoint(endpoint).createExchange();
        child.getIn().setBody(destination.body());

        Exception failure = null;
        try {
            producerTemplate.send(endpoint, child);
            failure = child.getException();
        } catch (Exception e) {
            failure = e;
        }

        if (failure == null) {
            return true;
        }

        log.error("[{}] Multicast destination '{}' failed — continuing with remaining destinations.",
                processorName, endpoint, failure);

        String deadLetter = config.getErrorHandler() != null ? config.getErrorHandler().getDeadLetter() : null;

        if (deadLetter != null) {
            try {
                producerTemplate.sendBody(deadLetter, destination.body());
                log.warn("[{}] Multicast destination '{}' failed — payload routed to dead letter '{}'.",
                        processorName, endpoint, deadLetter);
            } catch (Exception dlqFailure) {
                log.error("[{}] Multicast destination '{}' failed AND dead letter '{}' also failed — message lost.",
                        processorName, endpoint, deadLetter, dlqFailure);
            }
        } else {
            log.error("[{}] Multicast destination '{}' failed and no dead letter is configured — message lost.",
                    processorName, endpoint);
        }

        return false;
    }

    /**
     * Builds the choice() dispatch table for standard (non-Multicast) outcomes,
     * one branch per YAML binding. An outcome bound to a single endpoint becomes
     * a plain to(); bound to multiple endpoints becomes Camel's own multicast() EIP.
     */
    private void buildChoiceTable(ChoiceDefinition choice) {
        for (Map.Entry<String, List<String>> binding : config.getBindings().entrySet()) {
            String outcomeName = binding.getKey();
            List<String> endpoints = binding.getValue();

            if (endpoints == null || endpoints.isEmpty()) {
                log.warn("[{}] Outcome '{}' has no defined destination URIs — skipping branch.",
                        processorName, outcomeName);
                continue;
            }

            Class<?> outcomeClass = resolveOutcomeClass(outcomeName);
            if (outcomeClass == null) {
                continue; // already logged in resolveOutcomeClass — either non-sealed or unresolved
            }

            addBranch(choice, outcomeClass, outcomeName, endpoints);
        }

        choice.otherwise()
                .process(exchange -> {
                    Object outcome = exchange.getProperty(OUTCOME_PROPERTY);
                    String outcomeType = outcome == null ? "null" : outcome.getClass().getSimpleName();
                    log.error("[{}] Unhandled outcome type: {}. Check your YAML bindings.",
                            processorName, outcomeType);
                })
                .stop();
    }

    private void addBranch(ChoiceDefinition choice, Class<?> outcomeClass, String outcomeName, List<String> endpoints) {
        if (endpoints.size() == 1) {
            choice.when(exchange -> outcomeClass.isInstance(exchange.getProperty(OUTCOME_PROPERTY)))
                    .to(endpoints.get(0));
            log.info("[{}] Bound {} → {}", processorName, outcomeName, endpoints.get(0));
        } else {
            choice.when(exchange -> outcomeClass.isInstance(exchange.getProperty(OUTCOME_PROPERTY)))
                    .multicast()
                    .to(endpoints.toArray(new String[0]))
                    .endChoice();
            log.info("[{}] Bound {} → Multicast {}", processorName, outcomeName, endpoints);
        }
    }

    /**
     * Resolves a YAML binding key to its permitted subtype class.
     *
     * <p>Returns null in two distinct cases, both logged differently:
     * <ul>
     *   <li>{@code routeInterface} isn't sealed at all — expected for a
     *       Multicast-only route, logged at debug level.</li>
     *   <li>{@code routeInterface} is sealed but no permitted subtype matches
     *       {@code outcomeName} — almost always a typo in routes.yaml, logged
     *       as a warning so it doesn't fail silently.</li>
     * </ul>
     */
    private Class<?> resolveOutcomeClass(String outcomeName) {
        Class<?>[] permitted = routeInterface.getPermittedSubclasses();

        if (permitted == null) {
            log.debug("[{}] Skipping choice() branch for '{}' — {} is not a sealed hierarchy " +
                    "(likely a Multicast-only route).", processorName, outcomeName, routeInterface.getName());
            return null;
        }

        for (Class<?> candidate : permitted) {
            if (candidate.getSimpleName().equals(outcomeName)) {
                return candidate;
            }
        }

        log.warn("[{}] YAML binds '{}' but no permitted subtype of {} matches that name — " +
                "check for a typo in routes.yaml.", processorName, outcomeName, routeInterface.getName());
        return null;
    }
}