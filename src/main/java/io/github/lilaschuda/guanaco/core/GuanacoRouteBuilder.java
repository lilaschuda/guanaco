package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThrottlerConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.dsl.Processor;
import io.github.lilaschuda.guanaco.eip.Drop;
import io.github.lilaschuda.guanaco.eip.Multicast;
import io.github.lilaschuda.guanaco.eip.Split;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.AggregateDefinition;
import org.apache.camel.model.ChoiceDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.ResequenceDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.IdempotentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import org.apache.camel.model.ThrottleDefinition;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;

public class GuanacoRouteBuilder extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(GuanacoRouteBuilder.class);
    static final String OUTCOME_PROPERTY = "guanaco.outcome";

    private final Processor<? extends RouteOutcome<?>> processor;
    private final Class<? extends RouteOutcome<?>> routeInterface;
    private final RouteConfig config;
    private final String processorName;
    private final RouteOutcomeRegistry outcomeRegistry;
    private final Map<String, AggregationStrategy> aggregationStrategies;

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

        ProcessorDefinition<?> pipeline = route;

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

        ProcessorDefinition<?> afterProcess = pipeline.process(this::dispatchOutcome);

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

    private void configureErrorHandler() {
        if (config.getErrorHandler() != null && config.getErrorHandler().getDeadLetter() != null) {
            errorHandler(deadLetterChannel(config.getErrorHandler().getDeadLetter())
                    .maximumRedeliveries(config.getErrorHandler().getMaxRetries())
                    .useOriginalMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcessorDefinition wireIdempotent(ProcessorDefinition parent, GuanacoIdempotentConfig idempotentConfig) {
        log.info("[{}] Wiring Idempotent Consumer — messageIdHeader='{}', capacity={}, eager={}, " +
                "removeOnFailure={}, skipDuplicate={}",
                processorName, idempotentConfig.getMessageIdHeader(), idempotentConfig.resolveCapacity(),
                idempotentConfig.resolveEager(), idempotentConfig.resolveRemoveOnFailure(),
                idempotentConfig.resolveSkipDuplicate());

        IdempotentRepository memoryRepo =
                MemoryIdempotentRepository.memoryIdempotentRepository(idempotentConfig.resolveCapacity());

        IdempotentRepository repository = new LoggingIdempotentRepository(
                memoryRepo, processorName, idempotentConfig.getMessageIdHeader());

        // No .end() — returning the block itself so what follows nests
        // inside it as a genuine child, which is what Camel's model requires.
        return parent.idempotentConsumer(header(idempotentConfig.getMessageIdHeader()), repository)
                .eager(idempotentConfig.resolveEager())
                .removeOnFailure(idempotentConfig.resolveRemoveOnFailure())
                .skipDuplicate(idempotentConfig.resolveSkipDuplicate());
    }

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcessorDefinition wireAggregate(ProcessorDefinition parent, GuanacoAggregateConfig aggConfig) {
        AggregationStrategy strategy = aggregationStrategies.get(aggConfig.getStrategyRef());

        if (strategy == null) {
            throw new GuanacoRouteBuilderException(
                    "[" + processorName + "] aggregate.strategyRef '" + aggConfig.getStrategyRef() +
                    "' was not found among registered AggregationStrategy instances. Register it via " +
                    "GuanacoContext.registerAggregationStrategy(\"" + aggConfig.getStrategyRef() +
                    "\", ...) before calling wireRoutes().");
        }

        log.info("[{}] Wiring Aggregate — correlationHeader='{}', strategyRef='{}', " +
                "completionSize={}, completionTimeoutMs={}",
                processorName, aggConfig.getCorrelationHeader(), aggConfig.getStrategyRef(),
                aggConfig.getCompletionSize(), aggConfig.getCompletionTimeoutMs());

        AggregateDefinition aggregate = parent.aggregate(header(aggConfig.getCorrelationHeader()), strategy);

        if (aggConfig.getCompletionSize() != null) {
            aggregate = aggregate.completionSize(aggConfig.getCompletionSize());
        }
        if (aggConfig.getCompletionTimeoutMs() != null) {
            aggregate = aggregate.completionTimeout(aggConfig.getCompletionTimeoutMs());
        }

        return aggregate;
    }

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

    private void dispatchSplitItem(Exchange exchange) {
        Object item = exchange.getIn().getBody();

        if (!(item instanceof RouteOutcome<?> outcome)) {
            log.error("[{}] Split item is not a RouteOutcome ({}) — skipping.",
                    processorName, item == null ? "null" : item.getClass().getName());
            return;
        }

        if (!isRegistered(outcome)) {
            return;
        }

        String outcomeName = outcome.getClass().getSimpleName();
        List<String> endpoints = config.getUrisFor(outcomeName);

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

    private void fanOut(Exchange exchange) {
        Object outcomeProperty = exchange.getProperty(OUTCOME_PROPERTY);
        if (!(outcomeProperty instanceof Multicast multicast)) {
            log.error("[{}] fanOut invoked but no Multicast outcome present.", processorName);
            exchange.setRouteStop(true);
            return;
        }

        log.debug("[{}] Multicast — fanning out to {} destination(s)",
                processorName, multicast.destinations().size());

        int failureCount = 0;

        for (RouteOutcome<?> destination : multicast.destinations()) {
            if (!isRegistered(destination)) {
                failureCount++;
                continue;
            }

            String outcomeName = destination.getClass().getSimpleName();
            List<String> endpoints = config.getUrisFor(outcomeName);

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

    private boolean isRegistered(RouteOutcome<?> outcome) {
        String simpleName = outcome.getClass().getSimpleName();

        if (!outcomeRegistry.contains(simpleName)) {
            log.error("[{}] Rejected outcome of type '{}' ({}) — not found in the boot-time " +
                    "RouteOutcomeRegistry. This outcome was constructed at runtime but was never " +
                    "scanned; it may belong to a package outside the configured base package, or " +
                    "represent a programming error. Dispatch refused for safety.",
                    processorName, simpleName, outcome.getClass().getName());
            return false;
        }

        return true;
    }

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

    private void buildChoiceTable(ChoiceDefinition choice) {
        for (Map.Entry<String, List<BindingTarget>> binding : config.getBindings().entrySet()) {
            String outcomeName = binding.getKey();
            List<BindingTarget> targets = binding.getValue();

            if (targets == null || targets.isEmpty()) {
                log.warn("[{}] Outcome '{}' has no defined destination URIs — skipping branch.",
                        processorName, outcomeName);
                continue;
            }

            Class<?> outcomeClass = resolveOutcomeClass(outcomeName);
            if (outcomeClass == null) {
                continue; // already logged — either non-sealed or unresolved
            }

            addBranch(choice, outcomeClass, outcomeName, targets);
        }

        choice.otherwise()
                .process(exchange -> {
                    Object outcome = exchange.getProperty(OUTCOME_PROPERTY);
                    String outcomeType = outcome == null ? "null" : outcome.getClass().getSimpleName();
                    log.error("[{}] Unhandled outcome type: {}. Check your bindings config.",
                            processorName, outcomeType);
                })
                .stop();
    }

    private void addBranch(ChoiceDefinition choice, Class<?> outcomeClass, String outcomeName, List<BindingTarget> targets) {
        if (targets.size() == 1) {
            BindingTarget target = targets.get(0);
            GuanacoThrottlerConfig throttler = config.resolveThrottlerFor(target);
            GuanacoCircuitBreakerConfig cb = config.resolveCircuitBreakerFor(target);

            ChoiceDefinition branch = choice.when(exchange -> outcomeClass.isInstance(exchange.getProperty(OUTCOME_PROPERTY)));

            ProcessorDefinition<?> parent = branch;

            if (throttler != null) {
                log.info("[{}] Bound {} → {} (throttled: {} req / {}ms)",
                        processorName, outcomeName, target.getUri(),
                        throttler.getRequestsPerPeriod(), throttler.getTimePeriodMillis());
                parent = applyThrottle(parent, throttler);
            }

            if (cb != null) {
                log.info("[{}] Bound {} → {} (circuit breaker enabled)", processorName, outcomeName, target.getUri());
                GuanacoResilienceHelper.applyCircuitBreaker(parent, target.getUri(), cb);
            } else if (throttler != null) {
                // Throttle applied but no circuit breaker — the throttle
                // definition itself needs a plain .to(uri) child.
                ((ThrottleDefinition) parent).to(target.getUri());
            } else {
                // Neither policy applies — plain, unwrapped dispatch.
                // FIX: was incorrectly calling branch.when(...) a second time
                // here; should just be a plain .to() on the branch itself.
                log.info("[{}] Bound {} → {}", processorName, outcomeName, target.getUri());
                branch.to(target.getUri());
            }
        } else {
            // Multicast case — multiple static endpoints for one outcome.
            // Unrelated to the throttler/circuit-breaker logic above; unchanged.
            List<String> uris = targets.stream().map(BindingTarget::getUri).toList();
            choice.when(exchange -> outcomeClass.isInstance(exchange.getProperty(OUTCOME_PROPERTY)))
                    .multicast()
                    .to(uris.toArray(new String[0]))
                    .endChoice();
            log.info("[{}] Bound {} → Multicast {}", processorName, outcomeName, uris);
        }
    }

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

        log.warn("[{}] Bindings config binds '{}' but no permitted subtype of {} matches that name — " +
                "check for a typo.", processorName, outcomeName, routeInterface.getName());
        return null;
    }
    
    /**
     * Applies a throttle policy and returns the ThrottleDefinition itself,
     * WITHOUT attaching a .to(uri) call — the caller decides what nests inside
     * it. Kept local to GuanacoRouteBuilder (rather than
     * GuanacoResilienceHelper, alongside applyCircuitBreaker) specifically
     * because it needs constant(...), which is inherited from RouteBuilder
     * itself and not cleanly accessible from a standalone helper class.
     */
    private ThrottleDefinition applyThrottle(ProcessorDefinition<?> parent, GuanacoThrottlerConfig throttlerConfig) {
        ThrottleDefinition throttle = parent.throttle(constant(throttlerConfig.getRequestsPerPeriod()));

        throttle.setTimePeriodMillis(Long.toString(throttlerConfig.getTimePeriodMillis()));

        if (throttlerConfig.resolveAsyncDelayed()) {
            throttle.setAsyncDelayed("true");
        }
        if (throttlerConfig.resolveRejectExecution()) {
            throttle.setRejectExecution("true");
        }

        return throttle;
    }
}
