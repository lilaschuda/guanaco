package io.github.lilaschuda.guanaco.example;

public record ToFraudCheck(String body) implements OrderRoute<String> {}
