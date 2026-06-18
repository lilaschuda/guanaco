package org.guanaco.example;

public record ToFraudCheck(String body) implements OrderRoute<String> {}
