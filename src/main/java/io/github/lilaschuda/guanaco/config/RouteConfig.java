package io.github.lilaschuda.guanaco.config;

import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a single route entry parsed from routes.yaml.
 *
 * routes:
 *   OrderProcessor:
 *     from: kafka:orders
 *     bindings:
 *       ToInventory:  kafka:inventory-topic
 *       ToPayment:    activemq:payment-queue
 *     errorHandler:
 *       deadLetter:  kafka:dead-letter
 *       maxRetries:  3
 */
public class RouteConfig {

    private String from;
    private Map<String, List<String>> bindings = new LinkedHashMap<>();
    private ErrorHandlerConfig errorHandler;
    private GuanacoAggregateConfig aggregate;
    private GuanacoIdempotentConfig idempotent;
    private GuanacoResequenceConfig resequence;
    
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public Map<String, List<String>> getBindings() { return bindings; }

    public ErrorHandlerConfig getErrorHandler() { return errorHandler; }
    public void setErrorHandler(ErrorHandlerConfig errorHandler) { this.errorHandler = errorHandler; }

    /** Null if no {@code aggregate:} block was declared for this route. */
    public GuanacoAggregateConfig getAggregate() { return aggregate; }
    public void setAggregate(GuanacoAggregateConfig aggregate) { this.aggregate = aggregate; }

    public GuanacoIdempotentConfig getIdempotent() { return idempotent; }
    public void setIdempotent(GuanacoIdempotentConfig idempotent) { this.idempotent = idempotent; }
    
    public GuanacoResequenceConfig getResequence() { return resequence; }
    public void setResequence(GuanacoResequenceConfig resequence) { this.resequence = resequence; }

    public static class ErrorHandlerConfig {
        private String deadLetter;
        private int maxRetries = 0;

        public String getDeadLetter() { return deadLetter; }
        public void setDeadLetter(String deadLetter) { this.deadLetter = deadLetter; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    @JsonSetter("bindings")
    @SuppressWarnings("unchecked")
    public void setBindings(Map<String, Object> rawBindings) {
        this.bindings = new LinkedHashMap<>();
        if (rawBindings == null) return;

        for (Map.Entry<String, Object> entry : rawBindings.entrySet()) {
            Object value = entry.getValue();

            if (value instanceof List) {
                this.bindings.put(entry.getKey(), (List<String>) value);
            } else if (value != null) {
                this.bindings.put(entry.getKey(), List.of(value.toString()));
            }
        }
    }
}