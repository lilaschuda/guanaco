package io.github.lilaschuda.guanaco.config;

import io.github.lilaschuda.guanaco.api.RouteOutcome;
import org.jspecify.annotations.Nullable;
import org.apache.camel.model.SagaCompletionMode;
import org.apache.camel.model.SagaPropagation;

import java.util.List;

/**
 * Route-level Saga (long-running, compensable transaction) policy.
 *
 * <p>Wraps this route's dispatch (the choice table and everything it
 * dispatches to) in Camel's native {@code .saga()} — everything from
 * Idempotent/Resequence/Aggregate onward runs inside the saga's tracked
 * scope. Opt-in: presence of this config alone enables it, no separate
 * {@code enabled} flag, matching {@link GuanacoSampleConfig}/
 * {@link GuanacoThreadsConfig}'s convention rather than
 * circuitBreaker/throttler/delayer's inheritance-cancellation one, since
 * there's no route-vs-binding hierarchy here to cancel.
 *
 * <p>{@code compensation}/{@code completion} are {@code Class} references,
 * not instances — resolved once at boot via the same simple-class-name
 * binding lookup every other outcome type uses, giving compile-time
 * type-safety without needing a constructed instance at runtime (Camel
 * sends the original exchange, not some new payload, to these endpoints).
 * They live here rather than on {@code SagaStep} specifically because
 * Camel's {@code .saga()} resolves both to one fixed endpoint at
 * route-build time — there is no per-message override at the Camel level
 * to wrap.
 *
 * <p>{@code optionKeys} declares, once, the full set of option names this
 * route's saga steps may snapshot — Camel requires the key set fixed at
 * boot, even though each key's value is evaluated per exchange. Guanaco
 * pre-registers one {@code .option(key, ...)} per declared key at boot;
 * at runtime, a step's actual option values are copied into exchange
 * properties before the saga block runs, and Camel's own snapshotting
 * picks them up from there.
 */
public class GuanacoSagaConfig {

    private @Nullable String sagaServiceRef;
    private @Nullable SagaPropagation propagation;
    private @Nullable SagaCompletionMode completionMode;
    private @Nullable Long timeoutMs;
    private List<String> optionKeys = List.of();
    private @Nullable Class<? extends RouteOutcome<?>> compensation;
    private @Nullable Class<? extends RouteOutcome<?>> completion;

    /** Default constructor, used by Jackson when deserializing a saga block. */
    public GuanacoSagaConfig() { }

    /**
     * Gets the named, shared {@code CamelSagaService} to use, resolved as a
     * Spring bean — e.g. a {@code camel-lra}-backed service for real
     * distributed coordination. {@code null} uses a default
     * {@code InMemorySagaService} that {@code GuanacoContext} registers
     * automatically whenever any route configures Saga -- Camel's own
     * {@code .saga()} has no automatic fallback of its own (confirmed via
     * {@code SagaReifier.resolveSagaService()}: with no explicit service
     * or ref, it does a mandatory registry type-search and throws if
     * nothing is found).
     *
     * @return the Spring bean name of the saga service to use, or {@code null} for Camel's default
     */
    public @Nullable String getSagaServiceRef() { return sagaServiceRef; }

    /**
     * Sets the named, shared {@code CamelSagaService} to use.
     *
     * @param sagaServiceRef the Spring bean name of the saga service to use
     */
    public void setSagaServiceRef(@Nullable String sagaServiceRef) { this.sagaServiceRef = sagaServiceRef; }

    /**
     * Gets the saga propagation mode.
     *
     * @return the configured propagation mode, or {@code null} to use Camel's default (REQUIRED)
     */
    public @Nullable SagaPropagation getPropagation() { return propagation; }

    /**
     * Sets the saga propagation mode.
     *
     * @param propagation the propagation mode
     */
    public void setPropagation(@Nullable SagaPropagation propagation) { this.propagation = propagation; }

    /**
     * Gets the saga completion mode.
     *
     * @return the configured completion mode, or {@code null} to use Camel's default (AUTO)
     */
    public @Nullable SagaCompletionMode getCompletionMode() { return completionMode; }

    /**
     * Sets the saga completion mode.
     *
     * @param completionMode the completion mode
     */
    public void setCompletionMode(@Nullable SagaCompletionMode completionMode) { this.completionMode = completionMode; }

    /**
     * Gets the saga timeout in milliseconds, after which it auto-compensates.
     *
     * @return the configured timeout in milliseconds, or {@code null} for no timeout
     */
    public @Nullable Long getTimeoutMs() { return timeoutMs; }

    /**
     * Sets the saga timeout in milliseconds.
     *
     * @param timeoutMs the timeout in milliseconds
     */
    public void setTimeoutMs(@Nullable Long timeoutMs) { this.timeoutMs = timeoutMs; }

    /**
     * Gets the full set of option names this route's saga steps may snapshot.
     *
     * @return the declared option keys, possibly empty, never {@code null}
     */
    public List<String> getOptionKeys() { return optionKeys; }

    /**
     * Sets the full set of option names this route's saga steps may snapshot.
     *
     * @param optionKeys the option keys to declare, or {@code null} to declare none
     */
    public void setOptionKeys(@Nullable List<String> optionKeys) {
        this.optionKeys = optionKeys != null ? optionKeys : List.of();
    }

    /**
     * Gets the outcome type whose binding is this route's compensation endpoint.
     *
     * @return the compensation outcome class, or {@code null} if this saga has no compensation
     */
    public @Nullable Class<? extends RouteOutcome<?>> getCompensation() { return compensation; }

    /**
     * Sets the outcome type whose binding is this route's compensation endpoint.
     *
     * @param compensation the compensation outcome class
     */
    public void setCompensation(@Nullable Class<? extends RouteOutcome<?>> compensation) { this.compensation = compensation; }

    /**
     * Gets the outcome type whose binding is this route's completion endpoint.
     *
     * @return the completion outcome class, or {@code null} if this saga has no completion callback
     */
    public @Nullable Class<? extends RouteOutcome<?>> getCompletion() { return completion; }

    /**
     * Sets the outcome type whose binding is this route's completion endpoint.
     *
     * @param completion the completion outcome class
     */
    public void setCompletion(@Nullable Class<? extends RouteOutcome<?>> completion) { this.completion = completion; }
}