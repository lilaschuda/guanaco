package io.github.lilaschuda.guanaco.api;

import io.github.lilaschuda.guanaco.api.Drop;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DropTest {

    @Test
    void bodyIsAlwaysNull() {
        assertThat(Drop.INSTANCE.body()).isNull();
    }

    @Test
    void isASingleton() {
        assertThat(Drop.INSTANCE).isSameAs(Drop.INSTANCE);
    }

    @Test
    void implementsRouteOutcome() {
        assertThat(Drop.INSTANCE).isInstanceOf(RouteOutcome.class);
    }
}
