/**
 * Extension point for observing route execution — idempotent evaluation,
 * resequencing, aggregation completion, delay application, and outcome
 * dispatch/failure.
 *
 * <p>This package contains only the
 * {@link io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener}
 * contract itself. Guanaco's core module carries no telemetry vendor
 * dependency of any kind — not even an optional one. Vendor-backed
 * implementations, such as the Micrometer-based listener, ship in separate,
 * opt-in modules (see {@code guanaco-telemetry}) that depend on this
 * interface without core ever depending on them.
 *
 * <p>Strictly opt-in: nothing in the core engine calls a
 * {@code GuanacoTelemetryListener} automatically, and no route is required
 * to declare one. Developers who don't register a listener pay no cost.
 *
 * <p><b>Not covered by the v1.0 API freeze.</b> This package is unwired from
 * the engine as of v1.0 — no route-building code emits these events yet.
 * Its method signatures may still change in a future minor release, ahead
 * of any engine wiring work, without that being treated as a breaking
 * change under semantic versioning. Treat it as a preview of the intended
 * shape, not a locked contract.
 */
package io.github.lilaschuda.guanaco.api.telemetry;
