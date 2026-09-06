package io.github.lilaschuda.guanaco.scala213;

import io.github.lilaschuda.guanaco.api.RouteOutcome;

/**
 * A Java-declared sealed interface, implemented by Scala case classes in
 * SealedInteropSmokeTest.scala -- the candidate workaround for Scala's
 * `sealed trait` not emitting a real JVM PermittedSubclasses attribute
 * (confirmed empirically for both Scala 2.13 and Scala 3; see that file).
 * scala-maven-plugin's default Mixed compileOrder should let the Scala
 * test source reference this Java-compiled type without extra pom wiring.
 */
public sealed interface SmokeRouteJava extends RouteOutcome<String>
        permits SmokeJavaOptionA, SmokeJavaOptionB {
}