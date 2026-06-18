package io.github.lilaschuda.guanaco.example;

public record ToInventory(String body) implements OrderRoute<String> {}
