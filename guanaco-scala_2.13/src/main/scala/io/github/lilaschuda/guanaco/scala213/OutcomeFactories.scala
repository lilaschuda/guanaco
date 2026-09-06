package io.github.lilaschuda.guanaco.scala213

import io.github.lilaschuda.guanaco.api.{Multicast, RouteOutcome, SagaStep, WireTap}

import scala.jdk.CollectionConverters._

/**
 * Scala-idiomatic factory for [[Multicast]], accepting destinations as
 * varargs instead of requiring callers to wrap them in a `java.util.List`
 * first.
 *
 * Declared as a companion object sharing [[Multicast]]'s name -- Scala
 * keeps type names and term names in separate namespaces, so `Multicast`
 * the class (a type) and `Multicast` the object (a term) coexist without
 * conflict, the same relationship an ordinary case class has with its own
 * companion. `new Multicast(...)` resolves to the Java constructor;
 * `Multicast(...)` resolves to this object's `apply`.
 *
 * Usage:
 * {{{
 * override def process(exchange: Exchange): OrderRoute =
 *   Multicast(ToInventory(order), ToAudit(order))
 * }}}
 */
object Multicast {
  def apply(destinations: RouteOutcome[_]*): Multicast =
    new Multicast(destinations.asJava)
}

/**
 * Scala-idiomatic factory for [[WireTap]], giving named-argument clarity
 * that calling [[WireTap]]'s Java constructor directly from Scala cannot --
 * Scala's named-argument calling convention only reads real parameter
 * names when the callee was compiled with `-parameters`, which the
 * framework's own Java API is not guaranteed to be.
 *
 * Usage:
 * {{{
 * WireTap(primary = ToInventory(order), tap = ToAuditLog(order))
 * }}}
 */
object WireTap {
  def apply[T](primary: RouteOutcome[T], tap: RouteOutcome[_]): WireTap[T] =
    new WireTap(primary, tap)
}

/**
 * Scala-idiomatic factory for [[SagaStep]], collapsing Java's two
 * constructor overloads (with and without options) into one function
 * with a default argument.
 *
 * `options` is typed `Map[String, AnyRef]`, not `Map[String, Any]` --
 * the Java constructor expects `Map<String, Object>`, and Scala's `Any`
 * is not the same type as `java.lang.Object` from the type-checker's
 * perspective (`Any` also covers unboxed value types; `AnyRef` is the
 * direct correspondent of `Object`). Using `Any` here would not type-check
 * against the Java signature without an explicit, easy-to-forget cast.
 *
 * Usage:
 * {{{
 * SagaStep(ToInventory(order), options = Map("orderId" -> order.id()))
 * }}}
 */
object SagaStep {
  def apply[T](primary: RouteOutcome[T], options: Map[String, AnyRef] = Map.empty): SagaStep[T] =
    new SagaStep(primary, options.asJava)
}
