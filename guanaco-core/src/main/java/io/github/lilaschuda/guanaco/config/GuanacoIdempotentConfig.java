package io.github.lilaschuda.guanaco.config;

/**
 * Optional idempotent-consumer configuration for a route, declared as a
 * nested {@code idempotent:} block — modeled identically to {@code aggregate}
 * and {@code errorHandler}, not as a polymorphic "step" pipeline.
 *
 * <p>When present, duplicate messages (by {@code messageIdHeader}) are
 * filtered before ever reaching the route's processor. If both
 * {@code idempotent:} and {@code aggregate:} are declared on the same route,
 * idempotent filtering is always wired first, unconditionally — a
 * duplicate must never be allowed to corrupt an aggregation count or trigger
 * a spurious completion before it's recognized as a duplicate. This ordering
 * is not configurable.
 *
 * <p>{@code messageIdHeader} is resolved internally via Camel's type-safe
 * {@code header(name)} builder — a plain header name, never an interpreted
 * expression string.
 *
 * <p>Unlike {@code completionSize}/{@code completionTimeoutMs} on Aggregate
 * (where null means "don't call this DSL method at all"), a null value here
 * means "call the DSL method using Guanaco's hardcoded default" — every one
 * of these has a sensible default and is always wired, just with a
 * developer-overridable value.
 */
public class GuanacoIdempotentConfig {

    /** Default idempotent repository capacity, used when {@code capacity} is not set. */
    public static final int DEFAULT_CAPACITY = 10_000;
    /** Default {@code eager} setting, used when {@code eager} is not set. */
    public static final boolean DEFAULT_EAGER = true;
    /** Default {@code removeOnFailure} setting, used when {@code removeOnFailure} is not set. */
    public static final boolean DEFAULT_REMOVE_ON_FAILURE = true;
    /** Default {@code skipDuplicate} setting, used when {@code skipDuplicate} is not set. */
    public static final boolean DEFAULT_SKIP_DUPLICATE = true;

    private String messageIdHeader;
    private Integer capacity;
    private Boolean eager;
    private Boolean removeOnFailure;
    private Boolean skipDuplicate;

    /** Default constructor, used by Jackson when deserializing an idempotent block. */
    public GuanacoIdempotentConfig() { }

    /**
     * Gets the header used to identify a message for duplicate detection.
     *
     * @return the header used to identify a message for duplicate detection
     */
    public String getMessageIdHeader() { return messageIdHeader; }

    /**
     * Sets the header used to identify a message for duplicate detection.
     *
     * @param messageIdHeader the header used to identify a message for duplicate detection
     */
    public void setMessageIdHeader(String messageIdHeader) { this.messageIdHeader = messageIdHeader; }

    /**
     * Gets the explicitly configured repository capacity.
     *
     * @return the explicitly configured repository capacity, or {@code null} if not set
     */
    public Integer getCapacity() { return capacity; }

    /**
     * Sets the idempotent repository capacity.
     *
     * @param capacity the idempotent repository capacity
     */
    public void setCapacity(Integer capacity) { this.capacity = capacity; }

    /**
     * Resolves the effective repository capacity.
     *
     * @return the effective repository capacity, falling back to {@link #DEFAULT_CAPACITY} if unset
     */
    public int resolveCapacity() {
        return capacity != null ? capacity : DEFAULT_CAPACITY;
    }

    /**
     * Gets the explicitly configured eager-filtering state.
     *
     * @return the explicitly configured eager-filtering state, or {@code null} if not set
     */
    public Boolean getEager() { return eager; }

    /**
     * Sets whether duplicates are filtered before or after processing.
     *
     * @param eager whether duplicates are filtered before or after processing
     */
    public void setEager(Boolean eager) { this.eager = eager; }

    /**
     * Resolves the effective eager-filtering state.
     *
     * @return the effective eager-filtering state, falling back to {@link #DEFAULT_EAGER} if unset
     */
    public boolean resolveEager() {
        return eager != null ? eager : DEFAULT_EAGER;
    }

    /**
     * Gets the explicitly configured remove-on-failure state.
     *
     * @return the explicitly configured remove-on-failure state, or {@code null} if not set
     */
    public Boolean getRemoveOnFailure() { return removeOnFailure; }

    /**
     * Sets whether a message's id is removed from the repository if processing fails.
     *
     * @param removeOnFailure whether a message's id is removed from the repository if processing fails
     */
    public void setRemoveOnFailure(Boolean removeOnFailure) { this.removeOnFailure = removeOnFailure; }

    /**
     * Resolves the effective remove-on-failure state.
     *
     * @return the effective remove-on-failure state, falling back to {@link #DEFAULT_REMOVE_ON_FAILURE} if unset
     */
    public boolean resolveRemoveOnFailure() {
        return removeOnFailure != null ? removeOnFailure : DEFAULT_REMOVE_ON_FAILURE;
    }

    /**
     * Gets the explicitly configured skip-duplicate state.
     *
     * @return the explicitly configured skip-duplicate state, or {@code null} if not set
     */
    public Boolean getSkipDuplicate() { return skipDuplicate; }

    /**
     * Sets whether a detected duplicate is skipped rather than processed.
     *
     * @param skipDuplicate whether a detected duplicate is skipped rather than processed
     */
    public void setSkipDuplicate(Boolean skipDuplicate) { this.skipDuplicate = skipDuplicate; }

    /**
     * Resolves the effective skip-duplicate state.
     *
     * @return the effective skip-duplicate state, falling back to {@link #DEFAULT_SKIP_DUPLICATE} if unset
     */
    public boolean resolveSkipDuplicate() {
        return skipDuplicate != null ? skipDuplicate : DEFAULT_SKIP_DUPLICATE;
    }
}