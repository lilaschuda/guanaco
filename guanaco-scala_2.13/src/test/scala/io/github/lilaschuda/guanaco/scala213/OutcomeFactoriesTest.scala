package io.github.lilaschuda.guanaco.scala213

import io.github.lilaschuda.guanaco.api.RouteOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Minimal RouteOutcome fixtures, shaped like a real route's outcome
 * hierarchy, for exercising the factories against genuine RouteOutcome
 * instances rather than mocks.
 */
final case class FixtureA(value: String) extends RouteOutcome[String] {
  override def body(): String = value
}

final case class FixtureB(value: Int) extends RouteOutcome[Int] {
  override def body(): Int = value
}

/**
 * Toolchain and ergonomics smoke tests for OutcomeFactories.
 *
 * Note: the equivalent Kotlin factories (guanaco-kotlin's
 * OutcomeFactories.kt) have no dedicated test coverage of their own --
 * confirmed by searching guanaco-kotlin's test sources directly. This
 * suite exists so the Scala 2.13 module doesn't carry that same gap
 * forward.
 */
class OutcomeFactoriesTest {

  @Test
  def multicastFactoryAcceptsVarargsAndPreservesOrder(): Unit = {
    val a = FixtureA("first")
    val b = FixtureB(2)

    val multicast = Multicast(a, b)

    // Casting to a concrete type argument rather than letting Scala capture
    // destinations()'s wildcard return type (List<? extends RouteOutcome<?>>)
    // as a fresh existential -- safe because generics are erased at runtime,
    // but necessary for `a`/`b` to type-check as elements of the list below.
    val destinations: java.util.List[RouteOutcome[_]] =
      multicast.destinations().asInstanceOf[java.util.List[RouteOutcome[_]]]

    assertThat(destinations).containsExactly(a, b)
  }

  @Test
  def wireTapFactoryWiresPrimaryAndTapAndDelegatesBody(): Unit = {
    val primary = FixtureA("primary-payload")
    val tap = FixtureB(99)

    val wireTap = WireTap(primary = primary, tap = tap)

    assertThat(wireTap.primary()).isEqualTo(primary)
    assertThat(wireTap.tap()).isEqualTo(tap)
    assertThat(wireTap.body()).isEqualTo("primary-payload")
  }

  @Test
  def sagaStepFactoryDefaultsToEmptyOptions(): Unit = {
    val primary = FixtureA("no-options")

    val sagaStep = SagaStep(primary)

    assertThat(sagaStep.options()).isEmpty()
    assertThat(sagaStep.body()).isEqualTo("no-options")
  }

  @Test
  def sagaStepFactoryAcceptsNamedOptions(): Unit = {
    val primary = FixtureA("with-options")

    val sagaStep = SagaStep(primary, options = Map("orderId" -> "abc-123"))

    assertThat(sagaStep.options()).containsEntry("orderId", "abc-123")
  }
}