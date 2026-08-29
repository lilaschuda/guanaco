/**
 * Optional telemetry hooks for observing route execution — idempotent
 * evaluation, resequencing, aggregation completion, delay application, and
 * outcome dispatch/failure.
 *
 * <p>Strictly opt-in: nothing in the core engine calls a
 * {@link io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener}
 * automatically, and no route is required to declare one. Developers who
 * don't need telemetry pay no cost — the {@code micrometer-core} dependency
 * backing {@link io.github.lilaschuda.guanaco.api.telemetry.GuanacoMicrometerListener}
 * is declared {@code optional} and {@code provided}, so it is never pulled
 * onto a consumer's classpath transitively.
 *
 * <p><b>Not covered by the v1.0 API freeze.</b> This package is unwired from
 * the engine as of v1.0 — no route-building code emits these events yet.
 * Its method signatures may still change in a future minor release, ahead
 * of any engine wiring work, without that being treated as a breaking
 * change under semantic versioning. Treat it as a preview of the intended
 * shape, not a locked contract.
 */
package io.github.lilaschuda.guanaco.api.telemetry;
