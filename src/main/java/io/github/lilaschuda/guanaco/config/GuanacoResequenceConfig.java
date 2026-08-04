package io.github.lilaschuda.guanaco.config;

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

    public enum Mode { STREAM, BATCH }

    public static final int DEFAULT_STREAM_CAPACITY = 1000;
    public static final long DEFAULT_STREAM_TIMEOUT_MS = 1000L;
    public static final boolean DEFAULT_REJECT_OLD = true;

    private String sequenceHeader;
    private Mode mode;
    private Integer capacity;
    private Long timeoutMs;
    private Boolean rejectOld;

    public String getSequenceHeader() { return sequenceHeader; }
    public void setSequenceHeader(String sequenceHeader) { this.sequenceHeader = sequenceHeader; }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }

    public Long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Long timeoutMs) { this.timeoutMs = timeoutMs; }

    public Boolean getRejectOld() { return rejectOld; }
    public void setRejectOld(Boolean rejectOld) { this.rejectOld = rejectOld; }

    /** STREAM mode only — BATCH mode always uses whatever capacity/timeoutMs is explicitly set. */
    public int resolveStreamCapacity() {
        return capacity != null ? capacity : DEFAULT_STREAM_CAPACITY;
    }

    /** STREAM mode only. */
    public long resolveStreamTimeoutMs() {
        return timeoutMs != null ? timeoutMs : DEFAULT_STREAM_TIMEOUT_MS;
    }

    /** STREAM mode only. */
    public boolean resolveRejectOld() {
        return rejectOld != null ? rejectOld : DEFAULT_REJECT_OLD;
    }
}