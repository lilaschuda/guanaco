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

    public static final int DEFAULT_CAPACITY = 10_000;
    public static final boolean DEFAULT_EAGER = true;
    public static final boolean DEFAULT_REMOVE_ON_FAILURE = true;
    public static final boolean DEFAULT_SKIP_DUPLICATE = true;

    private String messageIdHeader;
    private Integer capacity;
    private Boolean eager;
    private Boolean removeOnFailure;
    private Boolean skipDuplicate;

    public String getMessageIdHeader() { return messageIdHeader; }
    public void setMessageIdHeader(String messageIdHeader) { this.messageIdHeader = messageIdHeader; }

    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }

    public int resolveCapacity() {
        return capacity != null ? capacity : DEFAULT_CAPACITY;
    }

    public Boolean getEager() { return eager; }
    public void setEager(Boolean eager) { this.eager = eager; }

    public boolean resolveEager() {
        return eager != null ? eager : DEFAULT_EAGER;
    }

    public Boolean getRemoveOnFailure() { return removeOnFailure; }
    public void setRemoveOnFailure(Boolean removeOnFailure) { this.removeOnFailure = removeOnFailure; }

    public boolean resolveRemoveOnFailure() {
        return removeOnFailure != null ? removeOnFailure : DEFAULT_REMOVE_ON_FAILURE;
    }

    public Boolean getSkipDuplicate() { return skipDuplicate; }
    public void setSkipDuplicate(Boolean skipDuplicate) { this.skipDuplicate = skipDuplicate; }

    public boolean resolveSkipDuplicate() {
        return skipDuplicate != null ? skipDuplicate : DEFAULT_SKIP_DUPLICATE;
    }
}