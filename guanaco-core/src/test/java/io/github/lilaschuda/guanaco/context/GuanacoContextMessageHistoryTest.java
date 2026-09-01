package io.github.lilaschuda.guanaco.context;

import io.github.lilaschuda.guanaco.api.telemetry.GuanacoTelemetryListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Message history recording has real per-node overhead, so it must only
 * turn on when a listener is actually registered to consume it — the same
 * all-or-nothing principle every other telemetry hook already follows.
 * Reuses the existing example fixture package
 * ({@code io.github.lilaschuda.guanaco.example}) rather than adding new
 * fixtures just for this.
 */
class GuanacoContextMessageHistoryTest {

    private GuanacoContext guanacoContext;

    @AfterEach
    void tearDown() throws Exception {
        if (guanacoContext != null) {
            guanacoContext.stop();
        }
    }

    @Test
    void telemetryListenerRegistered_enablesMessageHistory() throws Exception {
        guanacoContext = new GuanacoContext("io.github.lilaschuda.guanaco.example");
        ApplicationContext ctx = new StaticApplicationContext();
        guanacoContext.setApplicationContext(ctx);
        guanacoContext.registerTelemetryListener(new GuanacoTelemetryListener() {});

        guanacoContext.wireRoutes();

        assertThat(guanacoContext.isMessageHistory()).isTrue();
    }

    @Test
    void noTelemetryListener_messageHistoryStaysDisabled() throws Exception {
        guanacoContext = new GuanacoContext("io.github.lilaschuda.guanaco.example");
        ApplicationContext ctx = new StaticApplicationContext();
        guanacoContext.setApplicationContext(ctx);

        guanacoContext.wireRoutes();

        assertThat(guanacoContext.isMessageHistory()).isFalse();
    }
}
