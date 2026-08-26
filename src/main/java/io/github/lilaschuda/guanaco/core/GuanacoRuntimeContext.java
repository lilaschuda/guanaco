package io.github.lilaschuda.guanaco.core;

import org.apache.camel.AggregationStrategy;

import java.util.Map;

/**
 * The boot-time-global, cross-route context handed to every
 * GuanacoRouteBuilder constructed within one wireRoutes() call — as
 * distinct from a route's own per-route specifics (processor, routeInterface,
 * config, processorName), which stay as GuanacoRouteBuilder's own direct
 * constructor parameters since they're never shared across routes.
 *
 * <p>Built exactly once per wireRoutes() invocation, immediately after
 * RouteOutcomeRegistry.scan() and the aggregationStrategies/delayStrategies
 * snapshots are taken — every route built in that same wiring pass shares
 * this exact instance.
 *
 * <p>A future EIP needing its own named, boot-time registry (mirroring
 * strategyRef/delayStrategyRef) adds a field here, not a new
 * GuanacoRouteBuilder constructor parameter — this is the intended
 * extension point for that.
 */
public record GuanacoRuntimeContext(
        RouteOutcomeRegistry outcomeRegistry,
        Map<String, AggregationStrategy> aggregationStrategies,
        Map<String, GuanacoDelayStrategy> delayStrategies
) {}