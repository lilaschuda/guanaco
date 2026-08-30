package io.github.lilaschuda.guanaco.api;

import io.github.lilaschuda.guanaco.api.Multicast;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MulticastTest {

    record TestDestinationA(String body) implements RouteOutcome<String> {}
    record TestDestinationB(String body) implements RouteOutcome<String> {}

    @SuppressWarnings("unchecked")
    @Test
    void bodyReturnsTheDestinationList() {
        var a = new TestDestinationA("payload-a");
        var b = new TestDestinationB("payload-b");

        Multicast multicast = new Multicast(List.of(a, b));

        List<Object> body = (List<Object>) (List<?>) multicast.body();
        assertThat(body).containsExactly(a, b);
    }

    @Test
    void destinationsMatchesBody() {
        var a = new TestDestinationA("payload-a");
        Multicast multicast = new Multicast(List.of(a));

        assertThat(multicast.destinations()).isEqualTo(multicast.body());
    }

    @SuppressWarnings("unchecked")
    @Test
    void destinationListIsImmutable() {
        var a = new TestDestinationA("payload-a");
        Multicast multicast = new Multicast(new ArrayList<>(List.of(a)));

        List<Object> destinations = (List<Object>) (List<?>) multicast.destinations();

        assertThatThrownBy(() -> destinations.add(new TestDestinationB("x")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void implementsRouteOutcome() {
        Multicast multicast = new Multicast(List.of(new TestDestinationA("x")));
        assertThat(multicast).isInstanceOf(RouteOutcome.class);
    }
}