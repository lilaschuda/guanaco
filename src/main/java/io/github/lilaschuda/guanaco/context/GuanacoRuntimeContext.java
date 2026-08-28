package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.GuanacoDelayStrategy;
import org.apache.camel.AggregationStrategy;

import java.util.Map;

/**
 * The boot-time-global, cross-route context handed to every
 * {@link GuanacoRouteBuilder} constructed within one {@link GuanacoContext#wireRoutes()} call[cite: 32, 35, 36].
 *
 * @param outcomeRegistry the boot-time registry of concrete route outcomes[cite: 36, 38]
 * @param aggregationStrategies registered custom aggregation strategies[cite: 36]
 * @param delayStrategies registered custom delay computation strategies[cite: 36]
 */
record GuanacoRuntimeContext(
        RouteOutcomeRegistry outcomeRegistry,
        Map<String, AggregationStrategy> aggregationStrategies,
        Map<String, GuanacoDelayStrategy> delayStrategies
) {}