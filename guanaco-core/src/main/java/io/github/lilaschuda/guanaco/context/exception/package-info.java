/**
 * Exceptions thrown while inspecting, validating, and wiring
 * {@link io.github.lilaschuda.guanaco.api.GuanacoRoute}-annotated processors
 * into Camel routes — topology extraction, {@code routes.yaml}/{@code json}
 * structural and binding validation, forbidden-component checks, and final
 * route-graph construction.
 *
 * <p>{@code @NullMarked}: same non-null-by-default convention as the
 * {@code api} package.
 */
@NullMarked
package io.github.lilaschuda.guanaco.context.exception;

import org.jspecify.annotations.NullMarked;
