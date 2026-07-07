package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import io.github.lilaschuda.guanaco.eip.Drop;
import io.github.lilaschuda.guanaco.eip.Multicast;
import io.github.lilaschuda.guanaco.eip.Split;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.ChoiceDefinition;
import org.apache.camel.model.RouteDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Generates a Camel {@link RouteBuilder} route from a Guanaco {@link Processor}
 * and its corresponding {@link RouteConfig}.
 *
 * <p>A single route is generated per processor, with one {@code choice()}
 * table containing, in order: a Drop branch, a Split branch, a Multicast
 * branch, one branch per YAML-bound standard outcome, and a final
 * {@code otherwise()} that logs unhandled outcomes. The graph is entirely
 * static once built — it never changes shape at runtime regardless of load.
 *
 * <p><b>Split and Multicast are dispatched identically:</b> both resolve
 * their destination(s) by the runtime outcome's simple class name against
 * {@code routes.yaml} bindings, completely independent of any sealed
 * interface. This is deliberate: Split items are, per design, autonomous
 * messages the moment they're unrolled — they are not required to be
 * permitted subtypes of the originating processor's own sealed route
 * interface. This is what makes cross-cutting outcomes (e.g. a shared
 * {@code ToAuditLog} reused across many unrelated processors) possible;
 * Java's sealed-type rules would otherwise force every such outcome into
 * a single processor's own package/module.
 *
 * <p>Standard (non-Split, non-Multicast) outcomes are still matched by
 * runtime type identity ({@code isInstance}) against the processor's
 * declared sealed hierarchy — this is the compile-time-enforced path and
 * is unaffected by this distinction.
 *
 * <p><b>Exchange body discipline:</b> the exchange body only ever holds a
 * value that is semantically correct for the current position in the route
 * graph. Drop, Split, and Multicast outcomes are left untouched by
 * {@link #dispatchOutcome} — each branch sets the body explicitly, at the
 * point it actually needs to.
 *
 * <p><b>Delivery semantics for Split and Multicast:</b> both are best-effort
 * / fire-and-forget. A failed send to one destination does not stop
 * delivery to the rest. Failed sends are routed to the configured dead
 * letter endpoint ({@code errorHandler.deadLetter} in routes.yaml) if one is
 * set; otherwise the failure is logged loudly and the message is lost.
 *
 * <p><b>Split aggregation:</b> split-and-forget by default. An optional
 * Camel {@code AggregationStrategy} may be supplied on the {@link Split}
 * outcome to collect results using Camel's native splitter engine.
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

        ChoiceDefinition choice = route.choice();

        choice.when(this::isDrop)
                .stop();

        choice.when(this::isSplit)
                .split(splitExpression(), new GuanacoDelegatingAggregationStrategy(OUTCOME_PROPERTY, processorName))
                    .process(this::dispatchSplitItem)
                .end()
                .stop();

        choice.when(this::isMulticast)
                .process(this::fanOut)
                .stop();

        // Appends one branch per YAML-bound standard outcome, plus the final
        // otherwise(), onto this same choice — Drop/Split/Multicast above are
        // just earlier branches in the identical choice() block.
        buildChoiceTable(choice);
    }

    private void configureErrorHandler() {
        if (config.getErrorHandler() != null && config.getErrorHandler().getDeadLetter() != null) {
            errorHandler(deadLetterChannel(config.getErrorHandler().getDeadLetter())
                    .maximumRedeliveries(config.getErrorHandler().getMaxRetries())
                    .useOriginalMessage());
        }
    }

    /**
     * Invokes the processor and stores the outcome. Sets the exchange body
     * only for standard outcomes — Drop, Split, and Multicast intentionally
     * leave the body untouched here, since their own handlers set it
     * explicitly at the point it's actually needed.
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

        if (outcome instanceof Split || outcome instanceof Multicast) {
            return; // body set explicitly by the Split/Multicast branch itself
        }

        exchange.getIn().setBody(outcome.body());
    }

    private boolean isDrop(Exchange exchange) {
        return exchange.getProperty(OUTCOME_PROPERTY) instanceof Drop;
    }

    private boolean isSplit(Exchange exchange) {
        return exchange.getProperty(OUTCOME_PROPERTY) instanceof Split;
    }

    private boolean isMulticast(Exchange exchange) {
        return exchange.getProperty(OUTCOME_PROPERTY) instanceof Multicast;
    }

    /**
     * The Expression Camel's split() uses to obtain the list of items to
     * iterate. Reads the Split outcome already stored on the exchange by
     * dispatchOutcome — evaluated once per incoming message, not per item.
     *
     * Expression's evaluate() is generic over the requested result type;
     * split() always requests the raw iterable, so the resolved list is
     * simply cast to whatever type is asked for.
     */
    private Expression splitExpression() {
        return new Expression() {
            @Override
            public <T> T evaluate(Exchange exchange, Class<T> type) {
                Object outcome = exchange.getProperty(OUTCOME_PROPERTY);
                List<? extends RouteOutcome<?>> items;

                if (outcome instanceof Split split) {
                    items = split.items();
                } else {
                    log.error("[{}] splitExpression invoked but no Split outcome present — returning empty list.",
                            processorName);
                    items = List.of();
                }

                return type.cast(items);
            }
        };
    }

    /**
     * Runs once per item after Camel's splitter creates a sub-exchange for it.
     *
     * <p>Split items are dispatched by simple class name against
     * {@code routes.yaml} bindings — the exact same mechanism
     * {@link #sendToEndpoint} already provides for Multicast — deliberately
     * bypassing any sealed-interface check. A Split item is an autonomous
     * message the moment it's unrolled; it is never required to be a
     * permitted subtype of the originating processor's route interface,
     * which is what makes cross-cutting, reusable outcome types possible.
     *
     * <p>After dispatch, the sub-exchange body is set to the item's own
     * payload, so an optional user-supplied AggregationStrategy has a
     * meaningful value to combine.
     */
    private void dispatchSplitItem(Exchange exchange) {
        Object item = exchange.getIn().getBody();

        if (!(item instanceof RouteOutcome<?> outcome)) {
            log.error("[{}] Split item is not a RouteOutcome ({}) — skipping.",
                    processorName, item == null ? "null" : item.getClass().getName());
            return;
        }

        String outcomeName = outcome.getClass().getSimpleName();
        List<String> endpoints = config.getBindings().get(outcomeName);

        if (endpoints == null || endpoints.isEmpty()) {
            log.warn("[{}] No binding found for Split item '{}' — skipping", processorName, outcomeName);
            return;
        }

        log.debug("[{}] Split item '{}' → {} endpoint(s)", processorName, outcomeName, endpoints.size());

        for (String endpoint : endpoints) {
            sendToEndpoint(outcome, endpoint);
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
    private void fanOut(Exchange exchange) {
        Object outcomeProperty = exchange.getProperty(OUTCOME_PROPERTY);
        if (!(outcomeProperty instanceof Multicast multicast)) {
            log.error("[{}] fanOut invoked but no Multicast outcome present.", processorName);
            exchange.setRouteStop(true);
            return;
        }

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
     * stop the rest of the fan-out/split or the parent route.
     *
     * <p>On failure, the payload is routed to the configured dead letter
     * endpoint ({@code errorHandler.deadLetter} in routes.yaml), if one is
     * set. If no dead letter is configured, the failure is logged loudly —
     * the message is lost in that case.
     *
     * <p>Shared by both {@link #fanOut} (Multicast) and
     * {@link #dispatchSplitItem} (Split) — both resolve destinations by
     * simple class name and both get identical delivery guarantees.
     *
     * @return true if the send succeeded, false if it failed (regardless of
     *         whether it was successfully forwarded to a dead letter).
     */
    private boolean sendToEndpoint(RouteOutcome<?> destination, String endpoint) {
        log.debug("[{}] → {}", processorName, endpoint);

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

        log.error("[{}] Destination '{}' failed — continuing with remaining destinations.",
                processorName, endpoint, failure);

        String deadLetter = config.getErrorHandler() != null ? config.getErrorHandler().getDeadLetter() : null;

        if (deadLetter != null) {
            try {
                producerTemplate.sendBody(deadLetter, destination.body());
                log.warn("[{}] Destination '{}' failed — payload routed to dead letter '{}'.",
                        processorName, endpoint, deadLetter);
            } catch (Exception dlqFailure) {
                log.error("[{}] Destination '{}' failed AND dead letter '{}' also failed — message lost.",
                        processorName, endpoint, deadLetter, dlqFailure);
            }
        } else {
            log.error("[{}] Destination '{}' failed and no dead letter is configured — message lost.",
                    processorName, endpoint);
        }

        return false;
    }

    /**
     * Appends the choice() branches for standard outcomes (one per YAML
     * binding, matched by sealed-hierarchy isInstance checks) onto the given
     * choice, followed by a final otherwise() that logs unhandled outcomes.
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
                continue; // already logged — either non-sealed or unresolved
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
     * Resolves a YAML binding key to its permitted subtype class, for
     * standard outcomes only. Split and Multicast never call this — they
     * resolve by simple name directly against the bindings map, with no
     * sealed-hierarchy requirement.
     *
     * <p>Returns null in two distinct cases, both logged differently:
     * <ul>
     *   <li>{@code routeInterface} isn't sealed at all — expected for a
     *       Multicast/Split-only route, logged at debug level.</li>
     *   <li>{@code routeInterface} is sealed but no permitted subtype matches
     *       {@code outcomeName} — almost always a typo in routes.yaml, logged
     *       as a warning so it doesn't fail silently.</li>
     * </ul>
     */
    private Class<?> resolveOutcomeClass(String outcomeName) {
        Class<?>[] permitted = routeInterface.getPermittedSubclasses();

        if (permitted == null) {
            log.debug("[{}] Skipping choice() branch for '{}' — {} is not a sealed hierarchy " +
                    "(likely a Multicast/Split-only route).", processorName, outcomeName, routeInterface.getName());
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