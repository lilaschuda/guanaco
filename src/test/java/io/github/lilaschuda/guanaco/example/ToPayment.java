package io.github.lilaschuda.guanaco.example;

public record ToPayment(String body) implements OrderRoute<String> {}
