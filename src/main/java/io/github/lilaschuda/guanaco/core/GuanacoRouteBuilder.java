package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.model.AggregateDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.ResequenceDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;

/**
 * Generates a Camel {@link RouteBuilder} route from a Guanaco {@link Processor}
 * and its corresponding {@link RouteConfig}.
 *
 * <p>
 * A single route is generated per processor, with one {@code choice()} table
 * containing, in order: a Drop branch, a Split branch, a Multicast branch, one
 * branch per YAML-bound standard outcome, and a final {@code otherwise()} that
 * logs unhandled outcomes. The graph is entirely static once built — it never
 * changes shape at runtime regardless of load.
 *
 * <p>
 * <b>Split and Multicast are dispatched identically:</b> both resolve their
 * destination(s) by the runtime outcome's simple class name against
 * {@code routes.yaml} bindings, completely independent of any sealed interface.
 * This is deliberate: Split items are, per design, autonomous messages the
 * moment they're unrolled — they are not required to be permitted subtypes of
 * the originating processor's own sealed route interface. This is what makes
 * cross-cutting outcomes (e.g. a shared {@code ToAuditLog} reused across many
 * unrelated processors) possible; Java's sealed-type rules would otherwise
 * force every such outcome into a single processor's own package/module.
 *
 * <p>
 * Standard (non-Split, non-Multicast) outcomes are still matched by runtime
 * type identity ({@code isInstance}) against the processor's declared sealed
 * hierarchy — this is the compile-time-enforced path and is unaffected by this
 * distinction.
 *
 * <p>
 * <b>Defense in depth:</b> every Split/Multicast destination is checked against
 * the frozen, boot-time {@link RouteOutcomeRegistry} before dispatch,
 * independent of whether it has a YAML binding. BindingValidator guarantees the
 * *configured bindings* are legitimate at boot time, but a Split/Multicast list
 * is built by arbitrary processor code at runtime — this check catches an
 * outcome instance whose class was never part of the boot-time scan (wrong
 * package, a programming mistake) before it's ever sent anywhere, regardless of
 * whether a stale or coincidental binding might otherwise have matched it.
 *
 * <p>
 * <b>Exchange body discipline:</b> the exchange body only ever holds a value
 * that is semantically correct for the current position in the route graph.
 * Drop, Split, and Multicast outcomes are left untouched by
 * {@link #dispatchOutcome} — each branch sets the body explicitly, at the point
 * it actually needs to.
 *
 * <p>
 * <b>Delivery semantics for Split and Multicast:</b> both are best-effort /
 * fire-and-forget. A failed send to one destination does not stop delivery to
 * the rest. Failed sends are routed to the configured dead letter endpoint
 * ({@code errorHandler.deadLetter} in routes.yaml) if one is set; otherwise the
 * failure is logged loudly and the message is lost.
 *
 * <p>
 * <b>Split aggregation:</b> split-and-forget by default. An optional Camel
 * {@code AggregationStrategy} may be supplied on the {@link Split} outcome to
 * collect results using Camel's native splitter engine.
 *
 * <p>
 * <b>No runtime reflection:</b> class resolution during dispatch is always a
 * lookup against either the processor's own sealed hierarchy
 * ({@code getPermittedSubclasses()}, resolved once at configure time) or the
 * frozen {@link RouteOutcomeRegistry} built once at boot. No
 * {@code Class.forName}, no classpath scanning, and no dynamic class loading
 * occur anywhere in the per-message dispatch path.
 */
public class GuanacoRouteBuilder extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(GuanacoRouteBuilder.class);
    static final String OUTCOME_PROPERTY = "guanaco.outcome";

    private final Processor<? extends RouteOutcome<?>> processor;
    private final Class<? extends RouteOutcome<?>> routeInterface;
    private final RouteConfig config;
    private final String processorName;
    private final RouteOutcomeRegistry outcomeRegistry;
    private final Map<String, AggregationStrategy> aggregationStrategies;

    // Created once in configure(); Camel manages its lifecycle alongside the CamelContext.
    private ProducerTemplate producerTemplate;

    public GuanacoRouteBuilder(
            Processor<? extends RouteOutcome<?>> processorInstance,
            Class<? extends RouteOutcome<?>> routeInterface,
            RouteConfig config,
            String processorName,
            RouteOutcomeRegistry outcomeRegistry,
            Map<String, AggregationStrategy> aggregationStrategies) {
        this.processor = processorInstance;
        this.routeInterface = routeInterface;
        this.config = config;
        this.processorName = processorName;
        this.outcomeRegistry = outcomeRegistry;
        this.aggregationStrategies = aggregationStrategies;
    }

    @Override
    public void configure() throws Exception {
        producerTemplate = getContext().createProducerTemplate();
        configureErrorHandler();

        RouteDefinition route = from(config.getFrom())
                .routeId("guanaco-" + processorName);

        ProcessorDefinition pipeline = route;

        // Fixed order — Idempotent, then Resequence, then Aggregate. Not configurable.
        if (config.getIdempotent() != null) {
            pipeline = wireIdempotent(pipeline, config.getIdempotent());
        }

        if (config.getResequence() != null) {
            pipeline = wireResequence(pipeline, config.getResequence());
        }

        if (config.getAggregate() != null) {
            pipeline = wireAggregate(pipeline, config.getAggregate());
        }

        ProcessorDefinition afterProcess = pipeline.process(this::dispatchOutcome);

        ChoiceDefinition choice = afterProcess.choice();

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

        buildChoiceTable(choice);
    }

    /**
     * Wires a Camel native aggregate() step before the route's processor runs.
     * Incoming messages are held and correlated by {@code correlationHeader}
     * using Camel's type-safe header(name) expression builder — never an
     * interpreted expression string — and merged via the AggregationStrategy
     * registered under {@code strategyRef}. Only a completed (released)
     * exchange proceeds to dispatchOutcome and the choice() table beyond this
     * point; everything downstream of aggregation is unchanged from the
     * non-aggregate case.
     *
     * <p>
     * Structural shape (correlationHeader/strategyRef present, at least one
     * completion condition) is already validated by
     * {@link BindingValidator#validateAggregateConfig} before route building
     * ever starts. What's checked here, at route-compilation time, is whether
     * strategyRef actually resolves to a registered strategy — this can only be
     * known here, since it depends on what's been registered via
     * {@code GuanacoContext.registerAggregationStrategy(...)}, not on the
     * config file alone.
     *
     * @throws GuanacoRouteBuilderException if strategyRef doesn't resolve to a
     * registered AggregationStrategy.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcessorDefinition wireAggregate(ProcessorDefinition parent, GuanacoAggregateConfig aggConfig) {
        AggregationStrategy strategy = aggregationStrategies.get(aggConfig.getStrategyRef());

        if (strategy == null) {
            throw new GuanacoRouteBuilderException(
                    "[" + processorName + "] aggregate.strategyRef '" + aggConfig.getStrategyRef()
                    + "' was not found among registered AggregationStrategy instances. Register it via "
                    + "GuanacoContext.registerAggregationStrategy(\"" + aggConfig.getStrategyRef()
                    + "\", ...) before calling wireRoutes().");
        }

        log.info("[{}] Wiring Aggregate — correlationHeader='{}', strategyRef='{}', "
                + "completionSize={}, completionTimeoutMs={}",
                processorName, aggConfig.getCorrelationHeader(), aggConfig.getStrategyRef(),
                aggConfig.getCompletionSize(), aggConfig.getCompletionTimeoutMs());

        AggregateDefinition aggregate = parent.aggregate(header(aggConfig.getCorrelationHeader()), strategy);

        if (aggConfig.getCompletionSize() != null) {
            aggregate = aggregate.completionSize(aggConfig.getCompletionSize());
        }
        if (aggConfig.getCompletionTimeoutMs() != null) {
            aggregate = aggregate.completionTimeout(aggConfig.getCompletionTimeoutMs());
        }

        // No .end() — same reasoning as wireIdempotent above.
        return aggregate;
    }

    private void configureErrorHandler() {
        if (config.getErrorHandler() != null && config.getErrorHandler().getDeadLetter() != null) {
            errorHandler(deadLetterChannel(config.getErrorHandler().getDeadLetter())
                    .maximumRedeliveries(config.getErrorHandler().getMaxRetries())
                    .useOriginalMessage());
        }
    }

    /**
     * Invokes the processor and stores the outcome. Sets the exchange body only
     * for standard outcomes — Drop, Split, and Multicast intentionally leave
     * the body untouched here, since their own handlers set it explicitly at
     * the point it's actually needed.
     */
    private void dispatchOutcome(Exchange exchange) throws Exception {
        RouteOutcome<?> outcome = processor.process(exchange);

        if (outcome == null) {
            throw new GuanacoRouteBuilderException(
                    "[" + processorName + "] process() returned null. "
                    + "Use Drop.INSTANCE to explicitly discard a message.");
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
     * split() always requests the raw iterable, so the resolved list is simply
     * cast to whatever type is asked for.
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
     * <p>
     * Split items are dispatched by simple class name against
     * {@code routes.yaml} bindings — the exact same mechanism
     * {@link #sendToEndpoint} already provides for Multicast — deliberately
     * bypassing any sealed-interface check. A Split item is an autonomous
     * message the moment it's unrolled; it is never required to be a permitted
     * subtype of the originating processor's route interface, which is what
     * makes cross-cutting, reusable outcome types possible.
     *
     * <p>
     * Before any binding lookup, the item's runtime class is checked against
     * the frozen {@link RouteOutcomeRegistry} via {@link #isRegistered}.
     *
     * <p>
     * After dispatch, the sub-exchange body is set to the item's own payload,
     * so an optional user-supplied AggregationStrategy has a meaningful value
     * to combine.
     */
    private void dispatchSplitItem(Exchange exchange) {
        Object item = exchange.getIn().getBody();

        if (!(item instanceof RouteOutcome<?> outcome)) {
            log.error("[{}] Split item is not a RouteOutcome ({}) — skipping.",
                    processorName, item == null ? "null" : item.getClass().getName());
            return;
        }

        if (!isRegistered(outcome)) {
            return; // isRegistered already logged the rejection
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
     * <p>
     * Best-effort: a failed send is routed to the dead letter endpoint (if
     * configured) and logged, but does not stop the fan-out from continuing to
     * remaining destinations. Each destination is checked against the frozen
     * {@link RouteOutcomeRegistry} via {@link #isRegistered} before any binding
     * lookup or send is attempted.
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
            if (!isRegistered(destination)) {
                failureCount++;
                continue; // isRegistered already logged the rejection
            }

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
            log.warn("[{}] Multicast completed with {} failed/rejected send(s) — see errors above.",
                    processorName, failureCount);
        }

        exchange.setRouteStop(true);
    }

    /**
     * Defense-in-depth check: confirms the outcome's runtime class was part of
     * the boot-time {@link RouteOutcomeRegistry} scan. Rejects anything that
     * wasn't — a mistakenly constructed instance, a class from outside the
     * scanned package, or any other unmapped type that a processor's own logic
     * might accidentally place into a Split or Multicast collection.
     *
     * <p>
     * This is a pure map lookup against an already-frozen registry — no
     * reflection, no classpath access, and no dynamic class loading occur here
     * or anywhere else after boot.
     */
    private boolean isRegistered(RouteOutcome<?> outcome) {
        String simpleName = outcome.getClass().getSimpleName();

        if (!outcomeRegistry.contains(simpleName)) {
            log.error("[{}] Rejected outcome of type '{}' ({}) — not found in the boot-time "
                    + "RouteOutcomeRegistry. This outcome was constructed at runtime but was never "
                    + "scanned; it may belong to a package outside the configured base package, or "
                    + "represent a programming error. Dispatch refused for safety.",
                    processorName, simpleName, outcome.getClass().getName());
            return false;
        }

        return true;
    }

    /**
     * Sends a single destination's payload to a single endpoint. Failures are
     * logged and swallowed here — by design, one failed destination does not
     * stop the rest of the fan-out/split or the parent route.
     *
     * <p>
     * On failure, the payload is routed to the configured dead letter endpoint
     * ({@code errorHandler.deadLetter} in routes.yaml), if one is set. If no
     * dead letter is configured, the failure is logged loudly — the message is
     * lost in that case.
     *
     * <p>
     * Shared by both {@link #fanOut} (Multicast) and {@link #dispatchSplitItem}
     * (Split) — both resolve destinations by simple class name and both get
     * identical delivery guarantees.
     *
     * @return true if the send succeeded, false if it failed (regardless of
     * whether it was successfully forwarded to a dead letter).
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
     * Resolves a YAML binding key to its permitted subtype class, for standard
     * outcomes only. Split and Multicast never call this — they resolve by
     * simple name directly against the bindings map, with no sealed-hierarchy
     * requirement.
     *
     * <p>
     * Returns null in two distinct cases, both logged differently:
     * <ul>
     * <li>{@code routeInterface} isn't sealed at all — expected for a
     * Multicast/Split-only route, logged at debug level.</li>
     * <li>{@code routeInterface} is sealed but no permitted subtype matches
     * {@code outcomeName} — almost always a typo in routes.yaml, logged as a
     * warning so it doesn't fail silently.</li>
     * </ul>
     */
    private Class<?> resolveOutcomeClass(String outcomeName) {
        Class<?>[] permitted = routeInterface.getPermittedSubclasses();

        if (permitted == null) {
            log.debug("[{}] Skipping choice() branch for '{}' — {} is not a sealed hierarchy "
                    + "(likely a Multicast/Split-only route).", processorName, outcomeName, routeInterface.getName());
            return null;
        }

        for (Class<?> candidate : permitted) {
            if (candidate.getSimpleName().equals(outcomeName)) {
                return candidate;
            }
        }

        log.warn("[{}] YAML binds '{}' but no permitted subtype of {} matches that name — "
                + "check for a typo in routes.yaml.", processorName, outcomeName, routeInterface.getName());
        return null;
    }

    /**
     * Wires a Camel native idempotentConsumer() step before Aggregate (if any)
     * and before the route's processor. A duplicate — recognized via
     * messageIdHeader, resolved through Camel's type-safe header(name) builder
     * — is absorbed here and never reaches anything downstream. Uses
     * MemoryIdempotentRepository in v1.0, wrapped in
     * {@link LoggingIdempotentRepository} so a skipped duplicate is always
     * logged regardless of Camel's own internal logging configuration.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcessorDefinition wireIdempotent(ProcessorDefinition parent, GuanacoIdempotentConfig idempotentConfig) {
        log.info("[{}] Wiring Idempotent Consumer — messageIdHeader='{}', capacity={}, eager={}, "
                + "removeOnFailure={}, skipDuplicate={}",
                processorName, idempotentConfig.getMessageIdHeader(), idempotentConfig.resolveCapacity(),
                idempotentConfig.resolveEager(), idempotentConfig.resolveRemoveOnFailure(),
                idempotentConfig.resolveSkipDuplicate());

        IdempotentRepository memoryRepo
                = MemoryIdempotentRepository.memoryIdempotentRepository(idempotentConfig.resolveCapacity());

        IdempotentRepository repository = new LoggingIdempotentRepository(
                memoryRepo, processorName, idempotentConfig.getMessageIdHeader());

        // No .end() — returning the block itself so dispatchOutcome/choice()
        // (or a following Aggregate block) nests inside it as a real child.
        return parent.idempotentConsumer(header(idempotentConfig.getMessageIdHeader()), repository)
                .eager(idempotentConfig.resolveEager())
                .removeOnFailure(idempotentConfig.resolveRemoveOnFailure())
                .skipDuplicate(idempotentConfig.resolveSkipDuplicate());
    }

    /**
     * Wires a Camel native resequence() step. STREAM mode buffers within a
     * sliding window and releases as soon as ordering allows; BATCH mode
     * collects a full batch then releases it fully sorted. Neither branch calls
     * .end() — same reasoning as wireIdempotent/wireAggregate: this returns the
     * block itself so what follows nests inside it as a genuine child, which is
     * what Camel's model requires (an empty block with .end() called
     * immediately fails at route-build time with "Definition has no children").
     *
     * NOTE: .stream()/.batch()/.timeout()/.capacity()/.size()/.rejectOld() are
     * Camel's documented ResequenceDefinition DSL methods, but unverified
     * against your exact camel.version by an actual compile here. If any of
     * these method names or return types don't match, the compiler error will
     * point at the exact line — same pattern as the .split()/.end()/
     * MemoryIdempotentRepository issues hit earlier in this project.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcessorDefinition wireResequence(ProcessorDefinition parent, GuanacoResequenceConfig reseqConfig) {
        log.info("[{}] Wiring Resequence — sequenceHeader='{}', mode={}, capacity={}, timeoutMs={}, rejectOld={}",
                processorName, reseqConfig.getSequenceHeader(), reseqConfig.getMode(),
                reseqConfig.getCapacity(), reseqConfig.getTimeoutMs(), reseqConfig.getRejectOld());

        ResequenceDefinition resequence = parent.resequence(header(reseqConfig.getSequenceHeader()));

        if (reseqConfig.getMode() == GuanacoResequenceConfig.Mode.STREAM) {
            resequence = resequence.stream();
            resequence = resequence.timeout(reseqConfig.resolveStreamTimeoutMs());
            resequence = resequence.capacity(reseqConfig.resolveStreamCapacity());

            if (reseqConfig.resolveRejectOld()) {
                resequence = resequence.rejectOld();
            }
        } else {
            resequence = resequence.batch();
            if (reseqConfig.getCapacity() != null) {
                resequence = resequence.size(reseqConfig.getCapacity());
            }
            if (reseqConfig.getTimeoutMs() != null) {
                resequence = resequence.timeout(reseqConfig.getTimeoutMs());
            }
        }

        return resequence;
    }
}
