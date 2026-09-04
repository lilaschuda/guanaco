package io.github.lilaschuda.guanaco.config;

import org.jspecify.annotations.Nullable;

import org.apache.camel.util.concurrent.ThreadPoolRejectedPolicy;

/**
 * Route-level pipeline thread handoff — a single async boundary, placed
 * once right after ingress (after Sample, before Idempotent), shifting all
 * downstream processing for this route off the calling/consumer thread
 * and onto a pool.
 *
 * <p>Two mutually exclusive pool sources, mirroring Camel's own
 * {@code .threads()} DSL exactly:
 *
 * <ul>
 *   <li><b>Inline, Camel-managed pool</b> — {@code poolSize}/{@code maxPoolSize}/
 *       {@code threadName}/{@code rejectedPolicy}/{@code callerRunsWhenRejected}.
 *       Camel creates and owns the pool via its own {@code ExecutorServiceManager}.
 *       All fields are optional — an entirely empty {@code GuanacoThreadsConfig}
 *       is itself a valid configuration, equivalent to plain {@code .threads()}
 *       with no arguments, using Camel's own default pool sizing.
 *   <li><b>Named, shared pool</b> — {@code executorServiceRef}, a Spring bean
 *       name resolved against the {@code ApplicationContext} already wired
 *       into {@code GuanacoContext}. For sharing one pool across multiple
 *       routes/bindings without Guanaco needing to invent its own
 *       named-pool registry or manage executor lifecycle itself.
 * </ul>
 *
 * <p>{@code executorServiceRef} may not be combined with any inline pool
 * field — validated at boot. Unlike {@link GuanacoDelayerConfig}'s
 * {@code delayMs}/{@code delayStrategyRef}, this is mutual exclusion, not
 * a required choice: with neither set, Guanaco still wires a plain
 * {@code .threads()} handoff using Camel's defaults.
 *
 * <p>Route-level only for this phase — no per-binding thread handoff, to
 * avoid the sequential-nesting complexity that would introduce (a message
 * hopping threads twice) without a concrete driving use case yet.
 */
public class GuanacoThreadsConfig {

    private @Nullable Integer poolSize;
    private @Nullable Integer maxPoolSize;
    private @Nullable String threadName;
    private @Nullable ThreadPoolRejectedPolicy rejectedPolicy;
    private @Nullable Boolean callerRunsWhenRejected;

    private @Nullable String executorServiceRef;

    /** Default constructor, used by Jackson when deserializing a threads block. */
    public GuanacoThreadsConfig() { }

    /**
     * Gets the configured core pool size.
     *
     * @return the configured core pool size, or {@code null} to use Camel's default
     */
    public @Nullable Integer getPoolSize() { return poolSize; }

    /**
     * Sets the core pool size.
     *
     * @param poolSize the core pool size
     */
    public void setPoolSize(@Nullable Integer poolSize) { this.poolSize = poolSize; }

    /**
     * Gets the configured maximum pool size.
     *
     * @return the configured maximum pool size, or {@code null} to use Camel's default
     */
    public @Nullable Integer getMaxPoolSize() { return maxPoolSize; }

    /**
     * Sets the maximum pool size.
     *
     * @param maxPoolSize the maximum pool size
     */
    public void setMaxPoolSize(@Nullable Integer maxPoolSize) { this.maxPoolSize = maxPoolSize; }

    /**
     * Gets the configured thread name pattern.
     *
     * @return the configured thread name pattern, or {@code null} to use Camel's default
     */
    public @Nullable String getThreadName() { return threadName; }

    /**
     * Sets the thread name pattern.
     *
     * @param threadName the thread name pattern
     */
    public void setThreadName(@Nullable String threadName) { this.threadName = threadName; }

    /**
     * Gets the configured rejection policy.
     *
     * @return the configured rejection policy, or {@code null} to use Camel's default
     */
    public @Nullable ThreadPoolRejectedPolicy getRejectedPolicy() { return rejectedPolicy; }

    /**
     * Sets the rejection policy.
     *
     * @param rejectedPolicy the rejection policy
     */
    public void setRejectedPolicy(@Nullable ThreadPoolRejectedPolicy rejectedPolicy) { this.rejectedPolicy = rejectedPolicy; }

    /**
     * Gets whether the caller thread runs a rejected task as a fallback.
     *
     * @return whether the caller thread runs a rejected task as a fallback, or {@code null} to use Camel's default
     */
    public @Nullable Boolean getCallerRunsWhenRejected() { return callerRunsWhenRejected; }

    /**
     * Sets whether the caller thread runs a rejected task as a fallback.
     *
     * @param callerRunsWhenRejected whether the caller thread runs a rejected task as a fallback
     */
    public void setCallerRunsWhenRejected(@Nullable Boolean callerRunsWhenRejected) {
        this.callerRunsWhenRejected = callerRunsWhenRejected;
    }

    /**
     * Gets the configured named/shared executor service reference.
     *
     * @return the Spring bean name of the executor service to use, or {@code null} if using an inline pool instead
     */
    public @Nullable String getExecutorServiceRef() { return executorServiceRef; }

    /**
     * Sets the named/shared executor service reference.
     *
     * @param executorServiceRef the Spring bean name of the executor service to use
     */
    public void setExecutorServiceRef(@Nullable String executorServiceRef) { this.executorServiceRef = executorServiceRef; }
}