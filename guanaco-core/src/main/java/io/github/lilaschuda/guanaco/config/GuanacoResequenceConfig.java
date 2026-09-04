package io.github.lilaschuda.guanaco.config;

import org.jspecify.annotations.Nullable;

/**
 * Optional resequencing configuration for a route, declared as a nested
 * {@code resequence:} block — modeled identically to {@code aggregate} and
 * {@code idempotent}, not as a polymorphic "step" pipeline.
 *
 * <p>When present, and combined with {@code idempotent:} and/or
 * {@code aggregate:} on the same route, the fixed (non-configurable)
 * pipeline order is: Idempotent Consumer, then Resequence, then Aggregate.
 * Idempotent runs first to drop cheap duplicates before any buffer memory
 * is allocated for sequence tracking; Resequence runs before Aggregate so
 * an order-sensitive AggregationStrategy always receives messages in
 * strict sequence.
 *
 * <p>{@code sequenceHeader} is resolved internally via Camel's type-safe
 * {@code header(name)} builder — a plain header name, never an interpreted
 * expression string.
 *
 * <p>STREAM and BATCH mode have different validation shapes:
 * <ul>
 *   <li>STREAM — {@code capacity} and {@code timeoutMs} are both optional
 *       with sensible defaults; {@code rejectOld} is STREAM-only, defaults
 *       to {@code true}.</li>
 *   <li>BATCH — at least one of {@code capacity}/{@code timeoutMs} is
 *       required (matching Aggregate's completion-condition shape);
 *       {@code rejectOld} is rejected at boot if set, since it has no
 *       meaning in BATCH mode and its presence likely signals a
 *       copy-paste or mode typo.</li>
 * </ul>
 */
public class GuanacoResequenceConfig {

    /** Resequencing strategy: unbounded, timeout-driven ({@link #STREAM}), or fixed-size batches ({@link #BATCH}). */
    public enum Mode {
        /** Unbounded stream resequencing, driven by {@code capacity}/{@code timeoutMs}/{@code rejectOld}. */
        STREAM,
        /** Fixed-size batch resequencing, requiring at least one of {@code capacity}/{@code timeoutMs}. */
        BATCH
    }

    /** Default STREAM-mode capacity, used when {@code capacity} is not set. */
    public static final int DEFAULT_STREAM_CAPACITY = 1000;
    /** Default STREAM-mode timeout in milliseconds, used when {@code timeoutMs} is not set. */
    public static final long DEFAULT_STREAM_TIMEOUT_MS = 1000L;
    /** Default STREAM-mode {@code rejectOld} setting, used when {@code rejectOld} is not set. */
    public static final boolean DEFAULT_REJECT_OLD = true;

    private @Nullable String sequenceHeader;
    private @Nullable Mode mode;
    private @Nullable Integer capacity;
    private @Nullable Long timeoutMs;
    private @Nullable Boolean rejectOld;

    /** Default constructor, used by Jackson when deserializing a resequence block. */
    public GuanacoResequenceConfig() { }

    /**
     * Gets the header used to determine message sequence order.
     *
     * @return the header used to determine message sequence order, or {@code null} if not set
     */
    public @Nullable String getSequenceHeader() { return sequenceHeader; }

    /**
     * Sets the header used to determine message sequence order.
     *
     * @param sequenceHeader the header used to determine message sequence order
     */
    public void setSequenceHeader(@Nullable String sequenceHeader) { this.sequenceHeader = sequenceHeader; }

    /**
     * Gets the resequencing mode.
     *
     * @return the resequencing mode, or {@code null} if not set
     */
    public @Nullable Mode getMode() { return mode; }

    /**
     * Sets the resequencing mode.
     *
     * @param mode the resequencing mode
     */
    public void setMode(@Nullable Mode mode) { this.mode = mode; }

    /**
     * Gets the resequencer's buffer capacity.
     *
     * @return the explicitly configured capacity, or {@code null} if not set
     */
    public @Nullable Integer getCapacity() { return capacity; }

    /**
     * Sets the resequencer's buffer capacity.
     *
     * @param capacity the resequencer's buffer capacity
     */
    public void setCapacity(@Nullable Integer capacity) { this.capacity = capacity; }

    /**
     * Gets the resequencer's timeout in milliseconds.
     *
     * @return the explicitly configured timeout in milliseconds, or {@code null} if not set
     */
    public @Nullable Long getTimeoutMs() { return timeoutMs; }

    /**
     * Sets the resequencer's timeout in milliseconds.
     *
     * @param timeoutMs the resequencer's timeout in milliseconds
     */
    public void setTimeoutMs(@Nullable Long timeoutMs) { this.timeoutMs = timeoutMs; }

    /**
     * Gets whether out-of-sequence-and-too-old messages are rejected.
     *
     * @return the explicitly configured reject-old state, or {@code null} if not set; STREAM mode only
     */
    public @Nullable Boolean getRejectOld() { return rejectOld; }

    /**
     * Sets whether out-of-sequence-and-too-old messages are rejected.
     *
     * @param rejectOld whether out-of-sequence-and-too-old messages are rejected; STREAM mode only
     */
    public void setRejectOld(@Nullable Boolean rejectOld) { this.rejectOld = rejectOld; }

    /**
     * STREAM mode only — BATCH mode always uses whatever capacity/timeoutMs is explicitly set.
     *
     * @return the effective STREAM capacity, falling back to {@link #DEFAULT_STREAM_CAPACITY} if unset
     */
    public int resolveStreamCapacity() {
        return capacity != null ? capacity : DEFAULT_STREAM_CAPACITY;
    }

    /**
     * STREAM mode only.
     *
     * @return the effective STREAM timeout in milliseconds, falling back to
     *         {@link #DEFAULT_STREAM_TIMEOUT_MS} if unset
     */
    public long resolveStreamTimeoutMs() {
        return timeoutMs != null ? timeoutMs : DEFAULT_STREAM_TIMEOUT_MS;
    }

    /**
     * STREAM mode only.
     *
     * @return the effective reject-old state, falling back to {@link #DEFAULT_REJECT_OLD} if unset
     */
    public boolean resolveRejectOld() {
        return rejectOld != null ? rejectOld : DEFAULT_REJECT_OLD;
    }
}