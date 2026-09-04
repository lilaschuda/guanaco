package io.github.lilaschuda.guanaco.kotlin

import io.github.lilaschuda.guanaco.api.Multicast
import io.github.lilaschuda.guanaco.api.RouteOutcome
import io.github.lilaschuda.guanaco.api.SagaStep
import io.github.lilaschuda.guanaco.api.WireTap

/**
 * Kotlin-idiomatic factory for [Multicast], accepting destinations as
 * varargs instead of requiring callers to wrap them in a [List] first.
 *
 * Declared as a top-level function sharing [Multicast]'s name -- Kotlin
 * resolves this unambiguously against the real constructor since the
 * signatures differ (vararg here vs. a single `List` parameter there),
 * the same pattern `kotlin.collections.List(size) { ... }` uses.
 *
 * Usage:
 * ```
 * return Multicast(ToInventory(order), ToAudit(order))
 * ```
 */
fun Multicast(vararg destinations: RouteOutcome<*>): Multicast =
    Multicast(destinations.toList())

/**
 * Kotlin-idiomatic factory for [WireTap], giving named-argument clarity
 * that calling [WireTap]'s Java constructor directly from Kotlin cannot --
 * Kotlin's named-argument calling convention doesn't extend across the
 * Java interop boundary, even for the framework's own API.
 *
 * Usage:
 * ```
 * return wireTap(primary = ToInventory(order), tap = ToAuditLog(order))
 * ```
 */
fun <T : Any> wireTap(primary: RouteOutcome<T>, tap: RouteOutcome<*>): WireTap<T> =
    WireTap(primary, tap)

/**
 * Kotlin-idiomatic factory for [SagaStep], collapsing Java's two
 * constructor overloads (with and without options) into one function
 * with a default argument.
 *
 * Usage:
 * ```
 * return sagaStep(ToInventory(order), options = mapOf("orderId" to order.id()))
 * ```
 */
fun <T : Any> sagaStep(primary: RouteOutcome<T>, options: Map<String, Any> = emptyMap()): SagaStep<T> =
    SagaStep(primary, options)