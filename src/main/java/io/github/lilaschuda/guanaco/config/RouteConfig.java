package io.github.lilaschuda.guanaco.config;

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
    private Map<String, String> bindings;
    private ErrorHandlerConfig errorHandler;

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public Map<String, String> getBindings() { return bindings; }
    public void setBindings(Map<String, String> bindings) { this.bindings = bindings; }

    public ErrorHandlerConfig getErrorHandler() { return errorHandler; }
    public void setErrorHandler(ErrorHandlerConfig errorHandler) { this.errorHandler = errorHandler; }

    public static class ErrorHandlerConfig {
        private String deadLetter;
        private int maxRetries = 0;

        public String getDeadLetter() { return deadLetter; }
        public void setDeadLetter(String deadLetter) { this.deadLetter = deadLetter; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }
}
