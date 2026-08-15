package io.github.lilaschuda.guanaco.core;

import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import io.github.lilaschuda.guanaco.config.GuanacoThrottlerConfig;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.Resilience4jConfigurationDefinition;
import org.apache.camel.model.ThrottleDefinition;

public class GuanacoResilienceHelper {

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

        cb.setResilience4jConfiguration(r4j);
        cb.to(targetUri);
    }

}