package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.config.BindingTarget;
import io.github.lilaschuda.guanaco.example.async.AsyncScanFixtures;
import io.github.lilaschuda.guanaco.testutils.GuanacoRuntimeEnvironment;
import io.github.lilaschuda.guanaco.testutils.GuanacoTestSupport;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Proves the classpath-scanning path in GuanacoContext.wireRoutes() itself
 * -- not GuanacoRouteBuilder called directly the way
 * GuanacoRouteBuilderAsyncDispatchTest does -- correctly discovers,
 * filters in, and instantiates a real @GuanacoRoute-annotated
 * AsyncOutcomeProcessor class via Reflections, exactly the same way it
 * already does for Processor.
 *
 * Uses GuanacoTestSupport (real classpath scan, programmatic route config)
 * rather than a routes.yaml fixture, matching the pattern established for
 * the guanaco-kotlin coroutine bridge tests. Scans
 * io.github.lilaschuda.guanaco.example.async specifically -- see
 * AsyncScanFixtures's own javadoc for why that isolation matters for a
 * test that performs a real scan, unlike its sibling tests in this file's
 * own package.
 */
class GuanacoContextAsyncScanTest {

    private GuanacoRuntimeEnvironment env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.shutdown();
        }
    }

    @Test
    void realGuanacoRouteAnnotatedAsyncProcessor_isDiscoveredAndWiredByWireRoutes() throws Exception {
        BindingTarget target = new BindingTarget();
        target.setUri("mock:inventory");

        GuanacoTestSupport support = new GuanacoTestSupport("io.github.lilaschuda.guanaco.example.async")
                .route("RealAsyncProcessor", "direct:orders", Map.of("AsyncToInventory", List.of(target)));

        env = support.start();

        MockEndpoint inventory = env.getMock("mock:inventory");
        inventory.expectedBodiesReceived("hello");

        env.send("direct:orders", "hello");

        MockEndpoint.assertIsSatisfied(env.getApplicationContext(), 5, TimeUnit.SECONDS);
    }
}