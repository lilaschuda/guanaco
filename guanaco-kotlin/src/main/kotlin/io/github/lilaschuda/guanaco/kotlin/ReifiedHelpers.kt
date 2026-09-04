package io.github.lilaschuda.guanaco.kotlin

import io.github.lilaschuda.guanaco.api.RouteOutcome
import org.apache.camel.Exchange

/**
 * Reified, type-checked access to a star-projected [RouteOutcome]'s body.
 *
 * body() on RouteOutcome<*> returns Any?, since the concrete payload type
 * is erased at that call site -- e.g. unwrapping WireTap.tap():
 * RouteOutcome<*>, or an element of Multicast.destinations():
 * List<RouteOutcome<*>>. Kotlin's reified type parameter preserves the
 * actual requested type at the call site for a genuine runtime check --
 * something Java's type erasure cannot do; the equivalent Java cast
 * would be silent and unchecked.
 *
 * Deliberately non-nullable (`T : Any`): body() legitimately returning
 * null and body() returning the wrong type are different failures with
 * different messages below, and collapsing them behind a nullable T would
 * make `as? T ?: throw` ambiguous -- a null body would silently succeed a
 * nullable cast instead of surfacing as the distinct failure it is.
 *
 * Usage:
 * ```
 * val order: Order = wireTap.tap().bodyAs()
 * ```
 *
 * @throws IllegalStateException if the body is null or not an instance of [T]
 */
inline fun <reified T : Any> RouteOutcome<*>.bodyAs(): T {
    val value = body()
    checkNotNull(value) { "Expected body of type ${T::class.simpleName}, but body() was null" }
    check(value is T) { "Expected body of type ${T::class.simpleName}, got ${value::class.simpleName}" }
    return value
}

/**
 * Reified, type-safe extraction of a Camel [Exchange]'s in-message body --
 * the Kotlin alternative to the class-token idiom Processor's own
 * documentation shows in Java (`exchange.getIn().getBody(Order.class)`),
 * needed there only because of Java's type erasure.
 *
 * Camel's own `Message.getBody(Class<T>)` is itself `@Nullable` (see
 * org.apache.camel.Message) -- a real, not merely theoretical,
 * possibility, so the same non-null-with-clear-failure stance as
 * [RouteOutcome.bodyAs] applies here for the same reason.
 *
 * Usage:
 * ```
 * val order = exchange.bodyAs<Order>()
 * ```
 *
 * @throws IllegalStateException if the body is null
 */
inline fun <reified T : Any> Exchange.bodyAs(): T {
    val value = getIn().getBody(T::class.java)
    checkNotNull(value) { "Expected Exchange body of type ${T::class.simpleName}, but getBody() returned null" }
    return value
}