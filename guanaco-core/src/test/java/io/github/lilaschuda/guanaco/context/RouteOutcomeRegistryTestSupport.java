package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.context.RouteOutcomeRegistry;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import io.github.lilaschuda.guanaco.context.RouteOutcomeRegistry;
import io.github.lilaschuda.guanaco.context.RouteOutcomeRegistry;
import java.util.Arrays;
import java.util.Map;

/**
 * Test-only construction path for {@link RouteOutcomeRegistry}, allowing
 * tests to build a registry from an explicit, known list of classes instead
 * of performing a real classpath scan.
 *
 * <p>This class lives exclusively under src/test — it is not part of the
 * published guanaco artifact. Production code has no construction path
 * other than {@link RouteOutcomeRegistry#scan(String)}; this subclass exists
 * purely so tests can exercise {@code GuanacoRouteBuilder}'s registry checks
 * without depending on classpath scanning of test fixture packages.
 *
 * <p>Reuses {@link RouteOutcomeRegistry#buildRegistryMap} directly, so test
 * registries enforce the exact same simple-name-collision guarantee as a
 * real production scan — a test fixture with two colliding names fails the
 * same way a real misconfigured package would.
 *
 * <p>Kept in the same package as {@code RouteOutcomeRegistry} (rather than a
 * separate test-support package) specifically so it can call the
 * package-private/protected construction machinery without widening any
 * production-facing access modifiers beyond what {@code scan()} already
 * requires.
 */
public final class RouteOutcomeRegistryTestSupport extends RouteOutcomeRegistry {

    private RouteOutcomeRegistryTestSupport(Map<String, Class<? extends RouteOutcome<?>>> byName) {
        super(byName);
    }

    /**
     * Builds a frozen registry from an explicit list of outcome classes —
     * the test-fixture equivalent of {@link RouteOutcomeRegistry#scan}.
     */
    @SafeVarargs
    public static RouteOutcomeRegistry of(Class<? extends RouteOutcome<?>>... outcomeClasses) {
        Map<String, Class<? extends RouteOutcome<?>>> registry =
                buildRegistryMap(Arrays.asList(outcomeClasses), "explicit test fixture list");
        return new RouteOutcomeRegistryTestSupport(registry);
    }
}