package io.github.lilaschuda.guanaco.context;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

/**
 * Proves that loadConfig()'s new single-file-first, directory-fallback
 * logic leaves the existing single-file convention completely unaffected.
 *
 * <p>Deliberately reuses the exact same fixtures GuanacoContextTest already
 * relies on (the io.github.lilaschuda.guanaco.example package and the
 * classpath-root routes.yaml) rather than introducing new ones: with no
 * routes/ directory present anywhere on this module's test classpath, the
 * single-file path is the ONLY way wireRoutes()/start() could possibly
 * succeed here. If the new fallback logic were broken -- e.g. mistakenly
 * attempting directory-mode first, or always falling back regardless of
 * whether a single file exists -- this test would fail with a
 * GuanacoConfigException rather than wiring routes and routing correctly,
 * since there's no routes/ directory to fall back to. A passing test is
 * therefore itself sufficient proof the single-file path was used.
 *
 * <p>The complementary case -- proving the fallback actually activates
 * when no single file exists at all -- can't be tested this way within
 * this same module: routes.yaml already exists at the classpath root for
 * every other test here, so singleFileConfigExists() would always resolve
 * true for any real GuanacoContext instance running in this test module,
 * regardless of what other fixtures exist elsewhere. That would need its
 * own isolated test module or Surefire configuration to test properly
 * through the real class; the underlying logic is already verified
 * independently, both at the ConfigLoader level and via a faithful mirror
 * of loadConfig()'s exact decision logic.
 */
class GuanacoContextConfigFallbackTest {

    private GuanacoContext guanacoContext;

    @BeforeEach
    void setUp() throws Exception {
        guanacoContext = new GuanacoContext("io.github.lilaschuda.guanaco.example");
        ApplicationContext ctx = new StaticApplicationContext();
        guanacoContext.setApplicationContext(ctx);
        guanacoContext.wireRoutes();
        guanacoContext.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        guanacoContext.stop();
    }

    @Test
    void existingSingleFileConvention_stillWorksUnaffectedByTheNewFallbackLogic() throws Exception {
        MockEndpoint inventory = guanacoContext.getEndpoint("mock:inventory", MockEndpoint.class);
        inventory.expectedMessageCount(1);

        ProducerTemplate producer = guanacoContext.createProducerTemplate();
        producer.sendBody("direct:orders", "order-123");

        MockEndpoint.assertIsSatisfied(guanacoContext);
    }
}