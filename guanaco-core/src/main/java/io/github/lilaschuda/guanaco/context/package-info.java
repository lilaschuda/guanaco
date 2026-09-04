/**
 * Guanaco's core wiring: {@link io.github.lilaschuda.guanaco.context.GuanacoContext},
 * the main entry point, plus internal route-building machinery not part
 * of the public API.
 *
 * <p>{@code @NullMarked}: same non-null-by-default convention as the
 * {@code api} package. This governs {@link GuanacoContext}'s own public
 * surface; the package's internal, package-private classes aren't
 * separately audited here, since they're invisible to any consumer
 * regardless of their own nullness annotations.
 */
@NullMarked
package io.github.lilaschuda.guanaco.context;

import org.jspecify.annotations.NullMarked;
