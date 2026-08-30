/**
 * Micrometer-backed {@link io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener}
 * implementation.
 *
 * <p>This package lives in the standalone {@code guanaco-telemetry} module,
 * kept separate from guanaco-core specifically so that consumers who don't
 * want telemetry never pull Micrometer onto their classpath. Add the
 * {@code guanaco-telemetry} dependency, construct a
 * {@link io.github.lilaschuda.guanaco.telemetry.micrometer.GuanacoMicrometerListener},
 * and register it on your {@code GuanacoContext} before calling
 * {@code wireRoutes()} to opt in.
 *
 * <p>Not covered by the v1.0 API freeze. This module's engine wiring is
 * still in progress as of v1.1 planning — see the project ROADMAP.
 */
package io.github.lilaschuda.guanaco.telemetry.micrometer;
