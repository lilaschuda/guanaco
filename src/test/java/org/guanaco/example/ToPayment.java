package org.guanaco.example;

public record ToPayment(String body) implements OrderRoute<String> {}
