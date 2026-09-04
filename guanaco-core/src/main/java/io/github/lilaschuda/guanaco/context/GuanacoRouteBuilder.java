package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.AsyncOutcomeProcessor;
import io.github.lilaschuda.guanaco.context.exception.GuanacoRouteBuilderException;
import io.github.lilaschuda.guanaco.api.GuanacoDelayStrategy;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.config.GuanacoAggregateConfig;
import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoDelayerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoIdempotentConfig;
import io.github.lilaschuda.guanaco.config.GuanacoResequenceConfig;
import io.github.lilaschuda.guanaco.config.GuanacoSagaConfig;
import io.github.lilaschuda.guanaco.config.GuanacoSampleConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThreadsConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThrottlerConfig;
import io.github.lilaschuda.guanaco.config.RouteConfig;
import io.github.lilaschuda.guanaco.api.Processor;
import io.github.lilaschuda.guanaco.api.Drop;
import io.github.lilaschuda.guanaco.api.Multicast;
import io.github.lilaschuda.guanaco.api.OutcomeCallback;
import io.github.lilaschuda.guanaco.api.Split;
import io.github.lilaschuda.guanaco.api.WireTap;
import io.github.lilaschuda.guanaco.api.SagaStep;
import io.github.lilaschuda.guanaco.api.telemetry.RouteSpan;
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
import org.apache.camel.model.SagaDefinition;
import org.apache.camel.model.SamplingDefinition;
import org.apache.camel.model.ThreadsDefinition;
import org.apache.camel.spi.IdempotentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.camel.AsyncCallback;
import org.apache.camel.model.DelayDefinition;
import org.apache.camel.model.ThrottleDefinition;
import org.apache.camel.support.AsyncProcessorSupport;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;

/**
 * Constructs native Camel route graphs for a specific Guanaco processor.
 */
class GuanacoRouteBuilder extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(GuanacoRouteBuilder.class);
    static final String OUTCOME_PROPERTY = "guanaco.outcome";
    
    /**
     * Set by {@link #dispatchOutcome} when the outcome was a {@link WireTap}
     * wrapper, to the tap's own resolved binding URI. Read by the dynamic
     * {@code wireTap()} DSL step in {@link #configure()}. Absent (never set)
     * when the outcome isn't tapped -- {@link #hasPendingTap} is the guard
     * that keeps the wireTap step a genuine no-op in that case.
     */
    private static final String TAP_TARGET_PROPERTY = "guanaco.wireTap.targetUri";

    /**
     * Set alongside TAP_TARGET_PROPERTY to the tap outcome's own body.
     * Needed because the copy Camel's wireTap() creates inherits the
     * PRIMARY's body via shallow exchange.copy() (properties included) --
     * {@link #prepareTapCopy} overwrites it with this, on the copy only.
     */
    private static final String TAP_BODY_PROPERTY = "guanaco.wireTap.tapBody";

    /**
     * Set only on the tapped COPY exchange (via {@code onPrepare}, which runs
     * on the copy, never the original) so the route-scoped
     * {@code onException(Throwable.class)} handler in {@link #configure()}
     * can tell a tap failure apart from an ordinary main-flow dispatch
     * failure and only act on the former -- the ordinary dispatch paths
     * already have their own telemetry/dead-letter handling and must not be
     * double-logged or double-reported here.
     */
    private static final String TAP_MARKER_PROPERTY = "guanaco.wireTap.isTapCopy";

    /**
     * Prefix for the exchange properties {@link #applySagaOptions} sets
     * from a SagaStep's per-message option values, and that the
     * boot-time-registered {@code .option(key, ...)} expressions in
     * {@link #wireSaga} read back dynamically. Full property name is this
     * prefix plus the option key, e.g. {@code guanaco.saga.option.orderId}.
     */
    private static final String SAGA_OPTION_PROPERTY_PREFIX = "guanaco.saga.option.";
    
    // Exactly one of these two is ever non-null -- set once, at construction,
    // by whichever of the two constructors below was used. Everything in
    // this class reads whichever one is populated; there is no code path
    // where both, or neither, are set.
    private final Processor<? extends RouteOutcome<?>> syncProcessor;
    private final AsyncOutcomeProcessor<? extends RouteOutcome<?>> asyncProcessor;
    private final Class<? extends RouteOutcome<?>> routeInterface;
    private final RouteConfig config;
    private final String processorName;
    private final GuanacoRuntimeContext runtimeContext;

    private ProducerTemplate producerTemplate;

    /**
     * Constructs a route builder for a synchronous {@link Processor}.
     *
     * @param processorInstance the processor instance executing business logic
     * @param routeInterface the route interface implemented by the processor
     * @param config the route configuration options
     * @param processorName the name identifying this processor
     * @param runtimeContext the global boot-time runtime context
     */
    public GuanacoRouteBuilder(
            Processor<? extends RouteOutcome<?>> processorInstance,
            Class<? extends RouteOutcome<?>> routeInterface,
            RouteConfig config,
            String processorName,
            GuanacoRuntimeContext runtimeContext) {
        this.syncProcessor = processorInstance;
        this.asyncProcessor = null;
        this.routeInterface = routeInterface;
        this.config = config;
        this.processorName = processorName;
        this.runtimeContext = runtimeContext;
    }

    /**
     * Constructs a route builder for an {@link AsyncOutcomeProcessor}.
     *
     * @param processorInstance the processor instance executing business logic asynchronously
     * @param routeInterface the route interface implemented by the processor
     * @param config the route configuration options
     * @param processorName the name identifying this processor
     * @param runtimeContext the global boot-time runtime context
     */
    public GuanacoRouteBuilder(
            AsyncOutcomeProcessor<? extends RouteOutcome<?>> processorInstance,
            Class<? extends RouteOutcome<?>> routeInterface,
            RouteConfig config,
            String processorName,
            GuanacoRuntimeContext runtimeContext) {
        this.syncProcessor = null;
        this.asyncProcessor = processorInstance;
        this.routeInterface = routeInterface;
        this.config = config;
        this.processorName = processorName;
        this.runtimeContext = runtimeContext;
    }

    @Override
    public void configure() throws Exception {
        producerTemplate = getContext().createProducerTemplate();
        configureErrorHandler();

        // Wire Tap failures are strictly isolated to their own async thread
        // and never propagate to the main flow (see the wireTap() step
        // below). This route-scoped handler is how their failure surfaces
        // at all: unconditional SLF4J logging always, plus telemetry when a
        // listener is registered. Guarded by TAP_MARKER_PROPERTY so ordinary
        // main-flow dispatch failures (which already have their own
        // doFinally-based telemetry/dead-letter handling) are never
        // double-handled here. Registered unconditionally -- unlike the
        // resequence/dispatch telemetry hooks -- because the SLF4J logging
        // must happen even with no GuanacoTelemetryListener registered.
        onException(Throwable.class)
                .onWhen(exchange -> exchange.getProperty(TAP_MARKER_PROPERTY) != null)
                .handled(true)
                .process(this::recordWireTapFailure);

        // Boot-time short circuit for resequence rejections
        if (runtimeContext.telemetryListener() != null) {
            onException(org.apache.camel.processor.resequencer.MessageRejectedException.class)
                .handled(false)
                .process(exchange -> runtimeContext.telemetryListener().onResequenceEvent(processorName, true));
        }

        // Message history reporting. onCompletion() -- unlike a step
        // chained into the pipeline -- runs once the exchange's unit of
        // work finishes, REGARDLESS of how: normal completion, an
        // exception, or being stopped early (Drop, Sample-rejection,
        // idempotent skip-duplicate). That uniformity is exactly why this
        // is the right mechanism here: a routeStop-based stop (Drop,
        // Sample) skips every processor chained after it in the pipeline
        // -- including any reporting hook we might try to chain there --
        // so per-hook-point reporting (mirroring onOutcomeDispatched/
        // onOutcomeFailed's call sites) genuinely cannot cover those paths.
        // onCompletion sidesteps that entirely by not being a pipeline
        // step at all. Must be registered before from() -- Camel enforces
        // this and throws if routes already exist on this RouteBuilder.
        if (runtimeContext.telemetryListener() != null) {
            onCompletion().process(this::reportMessageHistory);
        }
        
        RouteDefinition route = from(config.getFrom())
                .routeId("guanaco-" + processorName);

        ProcessorDefinition<?> pipeline = route;

        // Sampling, if configured, gates admission before any other
        // processing -- a dropped message shouldn't pay for idempotent
        // dedup, resequencing, or aggregation. Fixed as the very first
        // stage, ahead of Idempotent/Resequence/Aggregate; not configurable.
        if (config.getSample() != null) {
            pipeline = wireSample(pipeline, config.getSample());
        }

        // Threads, if configured, hands the rest of this route's processing
        // off to a pool -- placed after Sample specifically so a burst of
        // noisy traffic is dropped on the (cheap, synchronous) ingress
        // thread rather than being enqueued into a bounded thread pool
        // first. Not configurable beyond this position.
        if (config.getThreads() != null) {
            pipeline = wireThreads(pipeline, config.getThreads());
        }

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

        ProcessorDefinition<?> afterProcess = asyncProcessor != null
                ? pipeline.process(new AsyncDispatchStep())
                : pipeline.process(this::dispatchOutcome);

        // If dispatchOutcome found a WireTap wrapper, it stashed the tap's
        // resolved target URI in TAP_TARGET_PROPERTY and unwrapped
        // OUTCOME_PROPERTY down to the primary outcome -- so everything
        // below this point (the choice table, bindings, delay/circuit
        // breaker/throttle) dispatches the primary exactly as if it had
        // been returned directly, with no knowledge that a tap happened.
        // hasPendingTap guards this into a genuine no-op the rest of the
        // time: an untapped message never touches the wireTap() DSL step
        // at all, not even to evaluate a dynamic URI against nothing.
        afterProcess.choice()
                .when(this::hasPendingTap)
                    .wireTap("${exchangeProperty[" + TAP_TARGET_PROPERTY + "]}")
                        .dynamicUri(true)
                        .onPrepare(this::prepareTapCopy)
                .end();

        // If Saga is configured, hand off to a SEPARATE, internal route
        // rather than wrapping .saga() around the rest of THIS route's
        // DSL chain. This was empirically necessary, not a style choice:
        // Camel's Saga processor evaluates .option(...) expressions (via
        // beginStep()) BEFORE invoking whatever it wraps, always -- and
        // testing confirmed dispatchOutcome (which sets the
        // SAGA_OPTION_PROPERTY_PREFIX properties .option(...) reads) was
        // still running AFTER that evaluation despite being positioned
        // earlier in the same route's DSL chain, for reasons that traced
        // through addOutput()/asType()/createChildProcessor() in Camel's
        // own reifier source without a conclusive explanation. A direct:
        // hop sidesteps the question entirely: DirectProducer passes the
        // exact same Exchange instance straight through with no copy, so
        // properties survive it fully, and the first route's processing
        // (dispatchOutcome, WireTap) is unconditionally, verifiably
        // complete before the second route -- containing only .saga() and
        // the choice table -- ever begins.
        ProcessorDefinition<?> beforeChoice;
        if (config.getSaga() != null) {
            String sagaInternalUri = "direct:guanaco-saga-internal-" + processorName;
            afterProcess.to(sagaInternalUri);

            RouteDefinition sagaRoute = from(sagaInternalUri).routeId("guanaco-" + processorName + "-saga");
            beforeChoice = wireSaga(sagaRoute, config.getSaga());
        } else {
            beforeChoice = afterProcess;
        }

        ChoiceDefinition choice = beforeChoice.choice();

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
    private ProcessorDefinition wireSample(ProcessorDefinition parent, GuanacoSampleConfig sampleConfig) {
        if (sampleConfig.getMessageFrequency() != null) {
            log.info("[{}] Wiring route-level Sample (ingress) — 1 in every {} messages",
                    processorName, sampleConfig.getMessageFrequency());
            return parent.sample(sampleConfig.getMessageFrequency());
        }

        log.info("[{}] Wiring route-level Sample (ingress) — at most 1 message per {}ms",
                processorName, sampleConfig.getSamplePeriodMillis());
        return parent.sample(Duration.ofMillis(sampleConfig.getSamplePeriodMillis()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcessorDefinition wireThreads(ProcessorDefinition parent, GuanacoThreadsConfig threadsConfig) {
        ThreadsDefinition threads = parent.threads();

        if (threadsConfig.getExecutorServiceRef() != null) {
            log.info("[{}] Wiring route-level Threads handoff — executorServiceRef='{}'",
                    processorName, threadsConfig.getExecutorServiceRef());
            return threads.executorService(threadsConfig.getExecutorServiceRef());
        }

        log.info("[{}] Wiring route-level Threads handoff — poolSize={}, maxPoolSize={}",
                processorName, threadsConfig.getPoolSize(), threadsConfig.getMaxPoolSize());

        if (threadsConfig.getPoolSize() != null) {
            threads = threads.poolSize(threadsConfig.getPoolSize());
        }
        if (threadsConfig.getMaxPoolSize() != null) {
            threads = threads.maxPoolSize(threadsConfig.getMaxPoolSize());
        }
        if (threadsConfig.getThreadName() != null) {
            threads = threads.threadName(threadsConfig.getThreadName());
        }
        if (threadsConfig.getRejectedPolicy() != null) {
            threads = threads.rejectedPolicy(threadsConfig.getRejectedPolicy());
        }
        if (threadsConfig.getCallerRunsWhenRejected() != null) {
            threads = threads.callerRunsWhenRejected(threadsConfig.getCallerRunsWhenRejected());
        }

        return threads;
    }

    /**
     * Wires the {@code .saga()} block itself. Never calls {@code .end()} --
     * deliberately, matching {@link #wireIdempotent}'s precedent -- so the
     * choice table (built on the return value, back in {@code configure()})
     * nests as a child of this saga scope rather than as a sibling after
     * it. compensation/completion URIs are resolved here from the already
     * boot-time-validated bindings (see
     * {@code BindingValidator#validateSagaConfig} -- guaranteed to exist
     * and be unambiguous by the time this runs).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcessorDefinition wireSaga(ProcessorDefinition parent, GuanacoSagaConfig sagaConfig) {
        log.info("[{}] Wiring Saga — propagation={}, completionMode={}, sagaServiceRef={}, timeoutMs={}, " +
                "optionKeys={}",
                processorName, sagaConfig.getPropagation(), sagaConfig.getCompletionMode(),
                sagaConfig.getSagaServiceRef(), sagaConfig.getTimeoutMs(), sagaConfig.getOptionKeys());

        SagaDefinition saga = parent.saga();

        if (sagaConfig.getPropagation() != null) {
            saga = saga.propagation(sagaConfig.getPropagation());
        }
        if (sagaConfig.getCompletionMode() != null) {
            saga = saga.completionMode(sagaConfig.getCompletionMode());
        }
        if (sagaConfig.getTimeoutMs() != null) {
            saga = saga.timeout(Duration.ofMillis(sagaConfig.getTimeoutMs()));
        }
        if (sagaConfig.getSagaServiceRef() != null) {
            saga = saga.sagaService(sagaConfig.getSagaServiceRef());
        }
        if (sagaConfig.getCompensation() != null) {
            String uri = config.getUrisFor(sagaConfig.getCompensation().getSimpleName()).get(0);
            saga = saga.compensation(uri);
        }
        if (sagaConfig.getCompletion() != null) {
            String uri = config.getUrisFor(sagaConfig.getCompletion().getSimpleName()).get(0);
            saga = saga.completion(uri);
        }
        for (String key : sagaConfig.getOptionKeys()) {
            saga = saga.option(key, exchangeProperty(SAGA_OPTION_PROPERTY_PREFIX + key));
        }

        return saga;
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

        if (runtimeContext.telemetryListener() != null) {
            IdempotentRepository delegate = repository;
            repository = new IdempotentRepository() {
                @Override public boolean add(String key) {
                    boolean isNew = delegate.add(key);
                    runtimeContext.telemetryListener().onIdempotentEvaluation(processorName, key, !isNew);
                    return isNew;
                }
                @Override public boolean contains(String key) { return delegate.contains(key); }
                @Override public boolean remove(String key) { return delegate.remove(key); }
                @Override public boolean confirm(String key) { return delegate.confirm(key); }
                @Override public void clear() { delegate.clear(); }
                @Override public void start() { delegate.start(); }
                @Override public void stop() { delegate.stop(); }
            };
        }
        
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

        if (runtimeContext.telemetryListener() != null) {
            // The rejected=true side is handled at the route level in
            // configure() via onException(MessageRejectedException.class),
            // since Camel throws that exception out of the resequencer node
            // itself, before any .process() chained here would run. This is
            // the complementary rejected=false side: every message that
            // makes it through resequencing to this point is reported as
            // processed.
            return resequence.process(this::recordResequenceProcessed);
        }

        return resequence;
    }

    private void recordResequenceProcessed(Exchange exchange) {
        runtimeContext.telemetryListener().onResequenceEvent(processorName, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProcessorDefinition wireAggregate(ProcessorDefinition parent, GuanacoAggregateConfig aggConfig) {
        AggregationStrategy strategy = runtimeContext.aggregationStrategies().get(aggConfig.getStrategyRef());

        if (strategy == null) {
            throw new GuanacoRouteBuilderException(
                    "[" + processorName + "] aggregate.strategyRef '" + aggConfig.getStrategyRef() +
                    "' was not found among registered AggregationStrategy instances. Register it via " +
                    "GuanacoContext.registerAggregationStrategy(\"" + aggConfig.getStrategyRef() +
                    "\", ...) before calling wireRoutes().");
        }

        if (runtimeContext.telemetryListener() != null) {
            AggregationStrategy delegate = strategy;
            strategy = new AggregationStrategy() {
                @Override
                public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
                    return delegate.aggregate(oldExchange, newExchange);
                }

                @Override
                public void onCompletion(Exchange exchange) {
                    if (exchange != null) {
                        String reason = exchange.getProperty(Exchange.AGGREGATED_COMPLETED_BY, String.class);
                        runtimeContext.telemetryListener().onAggregateComplete(processorName, reason != null ? reason : "completed");
                    }
                    delegate.onCompletion(exchange);
                }
            };
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
        @SuppressWarnings("unchecked")
        Processor<RouteOutcome<?>> typedSyncProcessor = (Processor<RouteOutcome<?>>) syncProcessor;
        RouteOutcome<?> outcome = typedSyncProcessor.process(exchange);
        finishDispatch(exchange, outcome);
    }

    /**
     * Everything dispatchOutcome (the sync path) and AsyncDispatchStep (the
     * async path) both need to do once an outcome is in hand -- extracted so
     * the SagaStep/WireTap unwrapping, OUTCOME_PROPERTY bookkeeping, and
     * Drop/Split/Multicast handling exist in exactly one place rather than
     * being maintained twice across the two dispatch mechanisms.
     */
    private void finishDispatch(Exchange exchange, RouteOutcome<?> outcome) {
        if (outcome == null) {
            throw new GuanacoRouteBuilderException(
                    "[" + processorName + "] process() returned null. " +
                    "Use Drop.INSTANCE to explicitly discard a message.");
        }

        if (outcome instanceof SagaStep<?> sagaStep) {
            applySagaOptions(exchange, sagaStep);
            outcome = sagaStep.primary();
        }

        if (outcome instanceof WireTap<?> wireTap) {
            resolveTapTarget(exchange, wireTap.tap());
            outcome = wireTap.primary();
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

    /**
     * The genuine {@link org.apache.camel.AsyncProcessor} wired into the
     * pipeline for the async path instead of {@link #dispatchOutcome}.
     * Extends {@code AsyncProcessorSupport} so the synchronous
     * {@code Processor#process(Exchange)} bridge Camel's own machinery may
     * still call comes for free -- only the two-arg async method is written
     * here.
     *
     * <p>{@code AsyncOutcomeProcessor} implementations are contractually
     * required to call exactly one {@link OutcomeCallback} method exactly
     * once (see its own javadoc), but a misbehaving implementation that
     * throws synchronously instead is caught defensively below -- without
     * that, {@code callback.done(...)} would never fire and this exchange
     * would hang permanently rather than surfacing as a route failure.
     */
    private class AsyncDispatchStep extends AsyncProcessorSupport {
        @Override
        public boolean process(Exchange exchange, AsyncCallback callback) {
            @SuppressWarnings("unchecked")
            AsyncOutcomeProcessor<RouteOutcome<?>> typedAsyncProcessor =
                    (AsyncOutcomeProcessor<RouteOutcome<?>>) asyncProcessor;

            try {
                typedAsyncProcessor.process(exchange, new OutcomeCallback<RouteOutcome<?>>() {
                    @Override
                    public void onOutcome(RouteOutcome<?> outcome) {
                        try {
                            finishDispatch(exchange, outcome);
                        } catch (Exception e) {
                            exchange.setException(e);
                        }
                        callback.done(false);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        exchange.setException(error);
                        callback.done(false);
                    }
                });
            } catch (Exception e) {
                exchange.setException(e);
                callback.done(true);
                return true;
            }

            return false; // genuinely async: callback fires later, not on this thread/call
        }
    }

    /**
     * Copies a {@link SagaStep}'s per-message option values onto the
     * exchange, as properties the boot-time-registered
     * {@code .option(key, ...)} expressions (see {@link #wireSaga}) read
     * dynamically -- the values are per-message, even though Camel
     * requires the set of option KEYS declared once, at boot.
     *
     * <p>Throws if this route has no {@code saga} config at all: a
     * processor returning {@code SagaStep} promises saga participation,
     * and if the route isn't actually wrapped in {@code .saga()}, these
     * option properties would be set for nothing -- no {@code .option()}
     * expression exists to ever read them, so the snapshot would be
     * silently lost. That's a genuine configuration mismatch between the
     * processor's own code and its routes.yaml/json, not something to
     * paper over by just skipping the saga-specific behavior quietly.
     *
     * <p>A key present in {@code sagaStep.options()} but not declared in
     * {@code optionKeys} is logged and skipped, not fatal -- consistent
     * with how an unrecognized runtime value is generally treated
     * elsewhere in this class (e.g. WireTap's missing-binding warning),
     * reserving hard failures for boot-time configuration problems.
     */
    private void applySagaOptions(Exchange exchange, SagaStep<?> sagaStep) {
        GuanacoSagaConfig sagaConfig = config.getSaga();
        if (sagaConfig == null) {
            throw new GuanacoRouteBuilderException(
                    "[" + processorName + "] process() returned a SagaStep, but this route has no 'saga' "
                    + "config declared. A SagaStep's options are only ever read by the .saga() block's own "
                    + "boot-time-registered .option(...) expressions -- with no saga config, none exist, so "
                    + "these values would be silently lost. Add a 'saga' block to this route's config.");
        }

        for (Map.Entry<String, Object> entry : sagaStep.options().entrySet()) {
            String key = entry.getKey();
            if (!sagaConfig.getOptionKeys().contains(key)) {
                log.warn("[{}] SagaStep option '{}' is not declared in saga.optionKeys — ignored. "
                        + "Declared keys: {}", processorName, key, sagaConfig.getOptionKeys());
                continue;
            }
            exchange.setProperty(SAGA_OPTION_PROPERTY_PREFIX + key, entry.getValue());
        }
    }

    /**
     * Resolves the Wire Tap's own binding (by simple class name, the same
     * lookup Multicast/Split destinations use) and stashes the target URI
     * and tap body for the wireTap() DSL step and {@link #prepareTapCopy}
     * to read. If no binding is found, logs a warning and leaves
     * TAP_TARGET_PROPERTY unset -- {@link #hasPendingTap} then correctly
     * treats this message as untapped rather than failing the whole
     * dispatch over a missing tap target; the primary outcome is
     * unaffected either way.
     */
    private void resolveTapTarget(Exchange exchange, RouteOutcome<?> tap) {
        if (!isRegistered(tap)) {
            return;
        }

        String tapOutcomeName = tap.getClass().getSimpleName();
        List<String> endpoints = config.getUrisFor(tapOutcomeName);

        if (endpoints == null || endpoints.isEmpty()) {
            log.warn("[{}] WireTap outcome '{}' has no binding — tap skipped, primary outcome still dispatched normally.",
                    processorName, tapOutcomeName);
            return;
        }

        if (endpoints.size() > 1) {
            log.warn("[{}] WireTap outcome '{}' has {} bindings — Wire Tap only supports one target; " +
                    "tapping to the first ('{}') only.",
                    processorName, tapOutcomeName, endpoints.size(), endpoints.get(0));
        }

        exchange.setProperty(TAP_TARGET_PROPERTY, endpoints.get(0));
        exchange.setProperty(TAP_BODY_PROPERTY, tap.body());
    }

    private boolean hasPendingTap(Exchange exchange) {
        return exchange.getProperty(TAP_TARGET_PROPERTY) != null;
    }

    /**
     * Runs on the tap's own COPY, never the original exchange -- this,
     * together with Camel's own async dispatch, is where Wire Tap's
     * main-flow isolation actually comes from: nothing done here can
     * affect the exchange continuing through the rest of this route.
     * Overwrites the copy's body (inherited from the primary via shallow
     * exchange.copy()) with the tap outcome's own body, and marks the copy
     * so the route-scoped onException(Throwable.class) handler recognizes
     * a failure here as a tap failure rather than a main-flow one.
     */
    private void prepareTapCopy(Exchange copy) {
        copy.getIn().setBody(copy.getProperty(TAP_BODY_PROPERTY));
        copy.setProperty(TAP_MARKER_PROPERTY, true);
    }

    /**
     * Unconditional SLF4J baseline for a Wire Tap failure, plus telemetry
     * when a listener is registered. The onException clause that invokes
     * this uses .handled(true) -- after this runs, the exchange is
     * considered successfully processed and the exception goes no further:
     * no redelivery, no dead-letter channel. That's deliberate, not an
     * oversight -- per the original design, Wire Tap failures rely
     * entirely on this logging/telemetry, never on the main route's error
     * handling, so a tapped failure never contaminates the main flow's DLQ.
     */
    private void recordWireTapFailure(Exchange exchange) {
        Throwable cause = exchange.getException();
        if (cause == null) {
            cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        }

        String targetUri = exchange.getProperty(TAP_TARGET_PROPERTY, String.class);

        log.error("[{}] Wire Tap failed — target='{}'", processorName, targetUri, cause);

        if (runtimeContext.telemetryListener() != null && cause != null) {
            runtimeContext.telemetryListener().onOutcomeFailed(processorName, targetUri, cause);
        }
    }

    /**
     * Reads Camel's own {@code Exchange.CamelMessageHistory} property —
     * populated automatically, node by node, whenever message history is
     * enabled on the CamelContext (see {@code GuanacoContext.wireRoutes()})
     * — converts it to Guanaco's own {@link RouteSpan} DTO, and reports it.
     * Only ever called from the {@code onCompletion()} registered in
     * {@link #configure()} when a listener is present, so no null-check on
     * the listener itself is needed here.
     */
    private void reportMessageHistory(Exchange exchange) {
        List<org.apache.camel.MessageHistory> history =
                exchange.getProperty(Exchange.MESSAGE_HISTORY, List.class);
        if (history == null || history.isEmpty()) {
            return;
        }

        List<RouteSpan> spans = new ArrayList<>(history.size());
        for (org.apache.camel.MessageHistory entry : history) {
            String nodeId = entry.getNode() != null ? entry.getNode().getId() : null;
            String nodeType = entry.getNode() != null ? entry.getNode().getShortName() : null;
            spans.add(new RouteSpan(entry.getRouteId(), nodeId, nodeType, entry.getElapsed()));
        }

        runtimeContext.telemetryListener().onMessageHistory(processorName, spans);
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

        if (!runtimeContext.outcomeRegistry().contains(simpleName)) {
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
        
        long startTime = runtimeContext.telemetryListener() != null ? System.currentTimeMillis() : 0;
        
        Exception failure = null;
        try {
            producerTemplate.send(endpoint, child);
            failure = child.getException();
        } catch (Exception e) {
            failure = e;
        }

        if (runtimeContext.telemetryListener() != null) {
            if (failure != null) {
                runtimeContext.telemetryListener().onOutcomeFailed(processorName, endpoint, failure);
            } else {
                long duration = System.currentTimeMillis() - startTime;
                String outcomeType = destination.getClass().getSimpleName();
                runtimeContext.telemetryListener().onOutcomeDispatched(processorName, outcomeType, endpoint, duration);
            }
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
                continue;
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
            GuanacoSampleConfig sample = target.getSample();
            GuanacoThrottlerConfig throttler = config.resolveThrottlerFor(target);
            GuanacoDelayerConfig delayer = config.resolveDelayerFor(target);
            GuanacoCircuitBreakerConfig cb = config.resolveCircuitBreakerFor(target);
            
            ChoiceDefinition branch = choice.when(exchange -> outcomeClass.isInstance(exchange.getProperty(OUTCOME_PROPERTY)));

            ProcessorDefinition<?> parent = branch;

            if (sample != null) {
                log.info("[{}] Bound {} → {} (sampled egress)", processorName, outcomeName, target.getUri());
                parent = applySample(parent, sample);
            }

            if (throttler != null) {
                log.info("[{}] Bound {} → {} (throttled: {} req / {}ms)",
                        processorName, outcomeName, target.getUri(),
                        throttler.getRequestsPerPeriod(), throttler.getTimePeriodMillis());
                parent = applyThrottle(parent, throttler);
            }

            if (delayer != null) {
                log.info("[{}] Bound {} → {} (delayed)", processorName, outcomeName, target.getUri());
                parent = applyDelay(parent, delayer, target.getUri());
            }

            if (cb != null) {
                log.info("[{}] Bound {} → {} (circuit breaker enabled)", processorName, outcomeName, target.getUri());
                GuanacoResilienceHelper.applyCircuitBreaker(parent, target.getUri(), cb, runtimeContext.telemetryListener(), processorName, outcomeName);
            } else {
                log.info("[{}] Bound {} → {}", processorName, outcomeName, target.getUri());
                attachPlainTo(parent, target.getUri(), outcomeName);
            }
        } else {
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
    
    private SamplingDefinition applySample(ProcessorDefinition<?> parent, GuanacoSampleConfig sampleConfig) {
        if (sampleConfig.getMessageFrequency() != null) {
            return parent.sample(sampleConfig.getMessageFrequency());
        }
        return parent.sample(Duration.ofMillis(sampleConfig.getSamplePeriodMillis()));
    }

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
    
    private void attachPlainTo(ProcessorDefinition<?> parent, String uri, String outcomeName) {
        if (runtimeContext.telemetryListener() == null) {
            if (parent instanceof ChoiceDefinition branch) {
                branch.to(uri);
            } else if (parent instanceof SamplingDefinition sampling) {
                sampling.to(uri);
            } else if (parent instanceof ThrottleDefinition throttle) {
                throttle.to(uri);
            } else if (parent instanceof DelayDefinition delay) {
                delay.to(uri);
            } else {
                throw new GuanacoRouteBuilderException(
                        "[" + processorName + "] Unexpected parent definition type: " + parent.getClass());
            }
        } else {
            parent.doTry()
                    .process(exchange -> exchange.setProperty("Guanaco_Start_" + uri, System.currentTimeMillis()))
                    .to(uri)
                    .process(exchange -> {
                        Long start = exchange.getProperty("Guanaco_Start_" + uri, Long.class);
                        long duration = start != null ? System.currentTimeMillis() - start : 0;
                        runtimeContext.telemetryListener().onOutcomeDispatched(processorName, outcomeName, uri, duration);
                    })
                    .doFinally()
                    .process(exchange -> {
                        // doFinally, unlike doCatch, needs no manual rethrow: Camel's
                        // FinallyProcessor automatically restores the original exception
                        // object after this block runs, regardless of what happens here.
                        // It also avoids doCatch's rewrap-as-RuntimeException problem for
                        // checked exceptions. By the time this runs, getException() has
                        // already been cleared and stashed as EXCEPTION_CAUGHT -- same
                        // fallback GuanacoResilienceHelper.applyCircuitBreaker uses.
                        Throwable cause = exchange.getException();
                        if (cause == null) {
                            cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                        }
                        if (cause != null) {
                            runtimeContext.telemetryListener().onOutcomeFailed(processorName, uri, cause);
                        }
                    })
                    .end();
        }
    }

    private DelayDefinition applyDelay(ProcessorDefinition<?> parent, GuanacoDelayerConfig delayerConfig, String targetUri) {
        Expression delayExpression;

        if (delayerConfig.getDelayStrategyRef() != null) {
            GuanacoDelayStrategy strategy = runtimeContext.delayStrategies().get(delayerConfig.getDelayStrategyRef());
            if (strategy == null) {
                throw new GuanacoRouteBuilderException(
                        "[" + processorName + "] delayer.delayStrategyRef '" + delayerConfig.getDelayStrategyRef()
                        + "' was not found among registered GuanacoDelayStrategy instances. Register it via "
                        + "GuanacoContext.registerDelayStrategy(\"" + delayerConfig.getDelayStrategyRef()
                        + "\", ...) before calling wireRoutes().");
            }
            delayExpression = new Expression() {
                @Override
                public <T> T evaluate(Exchange exchange, Class<T> type) {
                    return type.cast(strategy.computeDelayMs(exchange));
                }
            };
        } else {
            delayExpression = constant(delayerConfig.getDelayMs());
        }

        if (runtimeContext.telemetryListener() != null) {
            Expression delegate = delayExpression;
            delayExpression = new Expression() {
                @Override
                public <T> T evaluate(Exchange exchange, Class<T> type) {
                    Long delayMs = delegate.evaluate(exchange, Long.class);
                    if (delayMs != null && delayMs > 0) {
                        runtimeContext.telemetryListener().onDelayApplied(processorName, targetUri, delayMs);
                    }
                    return type.cast(delayMs);
                }
            };
        }
        
        DelayDefinition delay = parent.delay(delayExpression);

        if (delayerConfig.resolveAsyncDelayed()) {
            delay.asyncDelayed();
        }

        return delay;
    }
}