package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.Resilience4jConfigurationDefinition;

/**
 * Helper utility for applying Resilience4j circuit breaker configuration to Camel DSL definitions[cite: 34].
 */
class GuanacoResilienceHelper {

    /**
     * Applies a Resilience4j circuit breaker node to a route definition[cite: 34].
     *
     * @param route the parent processor definition node[cite: 34]
     * @param targetUri the endpoint URI to wrap[cite: 34]
     * @param config the circuit breaker configuration to apply[cite: 34]
     */
    public static void applyCircuitBreaker(
            ProcessorDefinition<?> route,
            String targetUri,
            GuanacoCircuitBreakerConfig config) {

        CircuitBreakerDefinition cb = route.circuitBreaker();

        Resilience4jConfigurationDefinition r4j = new Resilience4jConfigurationDefinition();

        if (config.getSlidingWindowSize() != null) {
            r4j.setSlidingWindowSize(String.valueOf(config.getSlidingWindowSize()));
        }
        if (config.getFailureRateThreshold() != null) {
            r4j.setFailureRateThreshold(String.valueOf(config.getFailureRateThreshold()));
        }
        if (config.getTimeoutDurationMs() != null) {
            r4j.setTimeoutDuration(String.valueOf(config.getTimeoutDurationMs()));
        }
        if (config.getMinimumNumberOfCalls() != null) {
            r4j.setMinimumNumberOfCalls(String.valueOf(config.getMinimumNumberOfCalls()));
        }
        
        if (config.getWaitDurationInOpenStateMs() != null) {
            r4j.setWaitDurationInOpenState(String.valueOf(config.getWaitDurationInOpenStateMs()));
        }

        cb.setResilience4jConfiguration(r4j);
        cb.to(targetUri);
    }

}