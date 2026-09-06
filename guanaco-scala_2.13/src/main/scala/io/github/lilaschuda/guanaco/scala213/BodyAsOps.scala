package io.github.lilaschuda.guanaco.scala213

import io.github.lilaschuda.guanaco.api.RouteOutcome
import org.apache.camel.Exchange

import scala.reflect.ClassTag

/**
 * Bundles the type-checked bodyAs helpers as implicit classes -- Scala 2.13
 * requires an implicit class to be defined inside a scope where method
 * definitions are allowed, not at the top level of a file, so both live
 * inside this wrapper object. Import its members to bring the extension
 * methods into scope:
 * {{{
 * import io.github.lilaschuda.guanaco.scala213.BodyAsOps._
 * }}}
 */
object BodyAsOps {

  /**
   * Type-checked access to a wildcard [[RouteOutcome]]'s body -- the Scala
   * counterpart to guanaco-kotlin's reified `RouteOutcome<*>.bodyAs<T>()`.
   *
   * body() on RouteOutcome[_] is erased at call sites like unwrapping
   * WireTap.tap(): RouteOutcome[_], or an element of
   * Multicast.destinations(): java.util.List[RouteOutcome[_]]. The
   * ClassTag context bound gives this method a real runtime Class to check
   * against -- Scala's standard substitute for Kotlin's `reified`, since
   * Scala type parameters are erased the same way Java's are.
   *
   * Usage:
   * {{{
   * val order: Order = wireTap.tap().bodyAs[Order]
   * }}}
   *
   * @throws IllegalStateException if the body is null or not an instance of T
   */
  implicit class RouteOutcomeBodyAsOps(private val outcome: RouteOutcome[_]) extends AnyVal {
    def bodyAs[T](implicit ct: ClassTag[T]): T = {
      val value = outcome.body()
      if (value == null) {
        throw new IllegalStateException(s"Expected body of type ${ct.runtimeClass.getSimpleName}, but body() was null")
      }
      if (!ct.runtimeClass.isInstance(value)) {
        throw new IllegalStateException(
          s"Expected body of type ${ct.runtimeClass.getSimpleName}, got ${value.getClass.getSimpleName}"
        )
      }
      value.asInstanceOf[T]
    }
  }

  /**
   * Type-safe extraction of a Camel [[Exchange]]'s in-message body -- the
   * Scala alternative to the class-token idiom Processor's own
   * documentation shows in Java (`exchange.getIn().getBody(Order.class)`).
   *
   * Camel's own `Message.getBody(Class<T>)` is itself `@Nullable`, so the
   * same non-null-with-clear-failure stance as [[RouteOutcomeBodyAsOps]]
   * applies here for the same reason.
   *
   * Usage:
   * {{{
   * val order = exchange.bodyAs[Order]
   * }}}
   *
   * @throws IllegalStateException if the body is null
   */
  implicit class ExchangeBodyAsOps(private val exchange: Exchange) extends AnyVal {
    def bodyAs[T](implicit ct: ClassTag[T]): T = {
      val value = exchange.getIn.getBody(ct.runtimeClass.asInstanceOf[Class[T]])
      if (value == null) {
        throw new IllegalStateException(
          s"Expected Exchange body of type ${ct.runtimeClass.getSimpleName}, but getBody() returned null"
        )
      }
      value
    }
  }
}