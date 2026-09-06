package io.github.lilaschuda.guanaco.scala213

import io.github.lilaschuda.guanaco.api.RouteOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

sealed trait SmokeRoute extends RouteOutcome[String]

final case class SmokeOptionA(value: String) extends SmokeRoute {
  override def body(): String = value
}

final case class SmokeOptionB(value: Int) extends SmokeRoute {
  override def body(): String = value.toString
}

final case class SmokeJavaOptionA(value: String) extends SmokeRouteJava {
  override def body(): String = value
}

final case class SmokeJavaOptionB(value: String) extends SmokeRouteJava {
  override def body(): String = value
}

class SealedInteropSmokeTest {

  /**
   * Negative regression guard, not an aspiration: confirmed empirically
   * (both Scala 2.13 and Scala 3) that a Scala `sealed trait` does NOT
   * emit a real JVM PermittedSubclasses attribute the way Kotlin's
   * `sealed interface` does -- Scala's own compilers keep sealed-hierarchy
   * bookkeeping in their own metadata format (pickle/TASTy) for their own
   * tooling, not as the plain JVM classfile attribute TopologyInspector's
   * Class.getPermittedSubclasses() reflection call depends on. This means
   * a Scala-authored `sealed trait` outcome hierarchy currently cannot
   * boot as a Guanaco route at all (TopologyInspector throws
   * GuanacoInspectionException unconditionally). If a future Scala
   * release changes this, this test starts failing here -- that's the
   * point: it should prompt revisiting SmokeRouteJava's workaround below,
   * not get silently ignored.
   */
  @Test
  def scalaSealedTraitExtendingRouteOutcomeIsNotACurrentlyGenuineJvmSealedType(): Unit = {
    val routeInterface = classOf[SmokeRoute]

    assertThat(routeInterface.isSealed).isFalse()
    assertThat(routeInterface.getPermittedSubclasses).isNull()
  }

  /**
   * The candidate workaround: declare the sealed interface in Java,
   * implement it with Scala case classes. Java's own sealed-interface
   * compilation is unaffected by anything about the Scala toolchain, so
   * this should produce a genuine JVM sealed type regardless.
   */
  @Test
  def javaDeclaredSealedInterfaceImplementedInScalaIsAGenuineJvmSealedType(): Unit = {
    val routeInterface = classOf[SmokeRouteJava]

    assertThat(routeInterface.isSealed).isTrue()

    val permitted = routeInterface.getPermittedSubclasses
    assertThat(permitted).isNotNull()
    assertThat(permitted)
      .hasSize(2)
      .allMatch(classOf[RouteOutcome[_]].isAssignableFrom(_))
  }
}