package io.github.lilaschuda.guanaco.kotlin

import io.github.lilaschuda.guanaco.api.RouteOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A Kotlin sealed interface extending [RouteOutcome], shaped exactly like
 * a real route's outcome hierarchy would be. Declared top-level (not
 * nested in the test class) to match precisely what was verified by hand
 * during v1.2.0 planning: a nested sealed type was never tested and is
 * not assumed to behave identically.
 */
sealed interface SmokeRoute : RouteOutcome<Void>

data class SmokeOptionA(val value: String) : SmokeRoute {
    override fun body(): Void? = null
}

data class SmokeOptionB(val value: Int) : SmokeRoute {
    override fun body(): Void? = null
}

/**
 * Toolchain smoke test for the guanaco-kotlin module.
 *
 * <p>This is not a trivial "does it compile" check. It's a compiled-in
 * regression guard for a specific, load-bearing fact verified by hand
 * during v1.2.0 planning: a Kotlin {@code sealed interface} extending
 * {@link RouteOutcome}, compiled with this module's toolchain (Kotlin
 * {@code kotlin.version}, JVM target 21), produces a genuine JVM-level
 * sealed type -- the real {@code PermittedSubclasses} classfile
 * attribute from JEP 409, not just Kotlin's own internal sealed-class
 * bookkeeping.
 *
 * <p>That specific fact is what {@code TopologyInspector} and
 * {@code BindingValidator} depend on: both call
 * {@code Class.getPermittedSubclasses()} directly to enumerate a
 * processor's declared route outcomes, and throw
 * {@code GuanacoInspectionException} if it returns {@code null}. A
 * Kotlin-authored route's entire compile-time-safety story rests on
 * this reflection call seeing what it expects to see.
 *
 * <p>If a future Kotlin compiler version, JVM target change, or build
 * configuration change ever silently broke this, this test fails
 * instead of the gap being rediscovered by hand later.
 */
class SealedInteropSmokeTest {

    @Test
    fun `Kotlin sealed interface extending RouteOutcome is a genuine JVM sealed type`() {
        val routeInterface = SmokeRoute::class.java

        assertThat(routeInterface.isSealed).isTrue()

        val permitted = checkNotNull(routeInterface.permittedSubclasses) {
            "${routeInterface.name} is not a sealed interface -- expected a genuine JVM sealed type"
        }
        assertThat(permitted.toList())
            .hasSize(2)
            .allMatch { RouteOutcome::class.java.isAssignableFrom(it) }
    }
}