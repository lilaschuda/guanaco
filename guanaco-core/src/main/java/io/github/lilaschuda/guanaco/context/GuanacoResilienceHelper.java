package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener;
import io.github.lilaschuda.guanaco.config.GuanacoCircuitBreakerConfig;
import org.apache.camel.Exchange;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.Resilience4jConfigurationDefinition;
import org.apache.camel.support.PropertyBindingSupport;

/**
 * Helper utility for applying Resilience4j circuit breaker configuration to Camel DSL definitions.
 */
class GuanacoResilienceHelper {

    /**
     * Applies a Resilience4j circuit breaker node to a route definition.
     *
     * @param route the parent processor definition node
     * @param targetUri the endpoint URI to wrap
     * @param config the circuit breaker configuration to apply
     */
    public static void applyCircuitBreaker(
            ProcessorDefinition<?> route,
            String targetUri,
            GuanacoCircuitBreakerConfig config,
            GuanacoTelemetryListener telemetryListener,
            String processorName,
            String outcomeName) {

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

        if (config.getExtra() != null && !config.getExtra().isEmpty()) {
            PropertyBindingSupport.build()
                    .withCamelContext(route.getCamelContext())
                    .withTarget(r4j)
                    .withProperties(config.getExtra())
                    .withIgnoreCase(true)
                    .bind();
        }
        
        cb.setResilience4jConfiguration(r4j);
        if (telemetryListener == null) {
            cb.to(targetUri);
        } else {
            cb.doTry()
                    .process(exchange -> exchange.setProperty("Guanaco_Start_" + targetUri, System.currentTimeMillis()))
                    .to(targetUri)
                    .process(exchange -> {
                        Long start = exchange.getProperty("Guanaco_Start_" + targetUri, Long.class);
                        long duration = start != null ? System.currentTimeMillis() - start : 0;
                        telemetryListener.onOutcomeDispatched(processorName, outcomeName, targetUri, duration);
                    })
                    .doFinally()
                    .process(exchange -> {
                        Throwable cause = exchange.getException();
                        if (cause == null) {
                            cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                        }
                        if (cause != null) {
                            telemetryListener.onOutcomeFailed(processorName, targetUri, cause.getCause());
                        }
                    })
                    .end();
        }
    }
}