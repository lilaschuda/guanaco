package io.github.lilaschuda.guanaco.eip;

import io.github.lilaschuda.guanaco.eip.Drop;
import io.github.lilaschuda.guanaco.core.RouteOutcome;
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
