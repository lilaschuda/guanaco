package io.github.lilaschuda.guanaco.config;

import org.jspecify.annotations.Nullable;

/**
 * Sampling policy — a hard, drop-based admission gate, not a queue like
 * {@link GuanacoThrottlerConfig}/{@link GuanacoDelayerConfig}. A message
 * that doesn't pass is discarded outright; nothing waits.
 *
 * <p>Declarable at two independent, non-hierarchical levels:
 *
 * <ul>
 *   <li>Route level ({@link RouteConfig#getSample()}) — applied once to the
 *       inbound stream, before Idempotent/Resequence/Aggregate even run.
 *       For reducing a noisy or bursty source before paying for any
 *       stateful processing.
 *   <li>Binding level ({@link BindingTarget#getSample()}) — applied only
 *       during dispatch to that one destination, after the rest of the
 *       route already ran. For controlling cost/load to one specific
 *       downstream (e.g. a paid third-party API) without affecting any
 *       other binding.
 * </ul>
 *
 * <p>Unlike circuitBreaker/throttler/delayer, there is no inheritance
 * between the two levels and no {@code enabled} flag: a route-level
 * sampler drops a message before any binding is ever reached, so a
 * binding cannot "opt out" of or override a decision already made at the
 * front door; a binding-level sampler only ever sees exchanges that
 * survived the rest of the route, independent of whatever the route-level
 * sampler (if any) already decided. Presence of a {@code sample} block is
 * itself the on/off signal, at both levels, independently.
 *
 * <p>Exactly one of {@code messageFrequency} or {@code samplePeriodMillis}
 * must be set — validated at boot, never lazily at route-wiring time.
 */
public class GuanacoSampleConfig {

    private @Nullable Long messageFrequency;
    private @Nullable Long samplePeriodMillis;

    /** Default constructor, used by Jackson when deserializing a sample block. */
    public GuanacoSampleConfig() { }

    /**
     * Gets the configured message frequency (1 out of every N messages passes).
     *
     * @return the configured message frequency, or {@code null} if using samplePeriodMillis instead
     */
    public @Nullable Long getMessageFrequency() { return messageFrequency; }

    /**
     * Sets the message frequency (1 out of every N messages passes).
     *
     * @param messageFrequency the message frequency
     */
    public void setMessageFrequency(@Nullable Long messageFrequency) { this.messageFrequency = messageFrequency; }

    /**
     * Gets the configured sample period in milliseconds (at most one message per window passes).
     *
     * @return the configured sample period in milliseconds, or {@code null} if using messageFrequency instead
     */
    public @Nullable Long getSamplePeriodMillis() { return samplePeriodMillis; }

    /**
     * Sets the sample period in milliseconds (at most one message per window passes).
     *
     * @param samplePeriodMillis the sample period in milliseconds
     */
    public void setSamplePeriodMillis(@Nullable Long samplePeriodMillis) { this.samplePeriodMillis = samplePeriodMillis; }
}