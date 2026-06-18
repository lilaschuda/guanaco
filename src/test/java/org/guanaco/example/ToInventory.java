package org.guanaco.example;

public record ToInventory(String body) implements OrderRoute<String> {}
