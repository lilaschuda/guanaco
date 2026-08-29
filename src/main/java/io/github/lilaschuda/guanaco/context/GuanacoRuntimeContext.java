package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.GuanacoDelayStrategy;
import org.apache.camel.AggregationStrategy;

import java.util.Map;

/**
 * The boot-time-global, cross-route context handed to every
 * {@link GuanacoRouteBuilder} constructed within one {@link GuanacoContext#wireRoutes()} call.
 *
 * @param outcomeRegistry the boot-time registry of concrete route outcomes
 * @param aggregationStrategies registered custom aggregation strategies
 * @param delayStrategies registered custom delay computation strategies
 */
record GuanacoRuntimeContext(
        RouteOutcomeRegistry outcomeRegistry,
        Map<String, AggregationStrategy> aggregationStrategies,
        Map<String, GuanacoDelayStrategy> delayStrategies
) {}