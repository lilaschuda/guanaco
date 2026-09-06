package io.github.lilaschuda.guanaco.scala3

import io.github.lilaschuda.guanaco.api.RouteOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

sealed trait SmokeRoute extends RouteOutcome[String]

final case class SmokeOptionA(value: String) extends SmokeRoute:
  override def body(): String = value

final case class SmokeOptionB(value: Int) extends SmokeRoute:
  override def body(): String = value.toString

final case class SmokeJavaOptionA(value: String) extends SmokeRouteJava:
  override def body(): String = value

final case class SmokeJavaOptionB(value: String) extends SmokeRouteJava:
  override def body(): String = value

class SealedInteropSmokeTest:

  /** See guanaco-scala_2.13's SealedInteropSmokeTest for the full rationale. */
  @Test
  def scalaSealedTraitExtendingRouteOutcomeIsNotACurrentlyGenuineJvmSealedType(): Unit =
    val routeInterface = classOf[SmokeRoute]

    assertThat(routeInterface.isSealed).isFalse()
    assertThat(routeInterface.getPermittedSubclasses).isNull()

  @Test
  def javaDeclaredSealedInterfaceImplementedInScalaIsAGenuineJvmSealedType(): Unit =
    val routeInterface = classOf[SmokeRouteJava]

    assertThat(routeInterface.isSealed).isTrue()

    val permitted = routeInterface.getPermittedSubclasses
    assertThat(permitted).isNotNull()
    assertThat(permitted)
      .hasSize(2)
      .allMatch(classOf[RouteOutcome[?]].isAssignableFrom(_))
