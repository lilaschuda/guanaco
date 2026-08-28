package io.github.lilaschuda.guanaco.api;

import io.github.lilaschuda.guanaco.api.Split;
import io.github.lilaschuda.guanaco.api.RouteOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import org.apache.camel.AggregationStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplitTest {

    record TestItemA(String body) implements RouteOutcome<String> {}
    record TestItemB(String body) implements RouteOutcome<String> {}

    @SuppressWarnings("unchecked")
    @Test
    void bodyReturnsTheItemList() {
        var a = new TestItemA("item-a");
        var b = new TestItemB("item-b");

        Split split = new Split(List.of(a, b));

        List<Object> body = (List<Object>) (List<?>) split.body();
        assertThat(body).containsExactly(a, b);
    }

    @Test
    void itemsMatchesBody() {
        var a = new TestItemA("item-a");
        Split split = new Split(List.of(a));

        assertThat(split.items()).isEqualTo(split.body());
    }

    @SuppressWarnings("unchecked")
    @Test
    void itemListIsImmutable() {
        var a = new TestItemA("item-a");
        Split split = new Split(new ArrayList<>(List.of(a)));

        List<Object> items = (List<Object>) (List<?>) split.items();

        assertThatThrownBy(() -> items.add(new TestItemB("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultConstructorHasNoAggregationStrategy() {
        Split split = new Split(List.of(new TestItemA("x")));
        assertThat(split.aggregationStrategy()).isNull();
    }

    @Test
    void overloadedConstructorCarriesAggregationStrategy() {
        AggregationStrategy strategy = (oldExchange, newExchange) -> newExchange;
        Split split = new Split(List.of(new TestItemA("x")), strategy);
        assertThat(split.aggregationStrategy()).isSameAs(strategy);
    }

    @Test
    void implementsRouteOutcome() {
        Split split = new Split(List.of(new TestItemA("x")));
        assertThat(split).isInstanceOf(RouteOutcome.class);
    }
}