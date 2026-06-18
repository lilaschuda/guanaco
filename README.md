# Guanaco

**Compiler-checked, idiomatic route definitions for Apache Camel — written entirely in native Java.**

Guanaco is a thin DSL layer on top of [Apache Camel](https://camel.apache.org/) that replaces Camel's fluent Java DSL and XML route definitions with plain Java: sealed interfaces, pattern matching, and records. Route topology is enforced by the compiler instead of discovered at runtime. Endpoint bindings live in a YAML file, so routes can be rewired in production — a ConfigMap update and a restart — without recompiling or redeploying new code.

Guanaco does not replace Camel's runtime, its component ecosystem, or its connectors. It sits on top of them.

> **Not affiliated with the Apache Software Foundation.** Guanaco is an independent, unofficial project built on top of Apache Camel. It is not endorsed by, sponsored by, or otherwise affiliated with the Apache Software Foundation. "Apache," "Camel," "Apache Camel," and the Apache feather logo are trademarks of The Apache Software Foundation.

## Why "Guanaco"?

The guanaco is a wild South American camelid — part of the same broader family as the camel, but from an entirely separate lineage, and the literal wild ancestor of the domesticated llama. The metaphor: Guanaco takes Camel's power and sets it loose. Same family, same strength, no harness — compiler-checked, idiomatic, unchained.

## The idea

A Camel route, in Guanaco, is a plain Java class implementing one method:

```java
@GuanacoRoute
public class OrderProcessor implements Processor<OrderRoute<?>> {

    @Override
    public OrderRoute<?> process(GuanacoMessage message) throws Exception {
        String body = message.getBody(String.class);
        if (body.contains("suspicious")) return new ToFraudCheck(body);
        if (body.contains("unpaid"))     return new ToPayment(body);
        return new ToInventory(body);
    }
}
```

The set of possible outcomes is declared as a sealed interface:

```java
public sealed interface OrderRoute<T> extends RouteOutcome<T>
    permits ToInventory, ToPayment, ToFraudCheck {}

record ToInventory(String body)  implements OrderRoute<String> {}
record ToPayment(String body)    implements OrderRoute<String> {}
record ToFraudCheck(String body) implements OrderRoute<String> {}
```

The compiler enforces that every branch is handled. There is no `.choice().when(...).otherwise()` chain to get wrong, and no way to silently forget a routing case — it won't compile.

Where each outcome actually goes is configured separately, in `routes.yaml`:

```yaml
routes:
  OrderProcessor:
    from: kafka:orders
    bindings:
      ToInventory:  kafka:inventory-topic
      ToPayment:    activemq:payment-queue
      ToFraudCheck: http:fraud-service/check
```

Code declares *what* can happen. Configuration declares *where it goes*. Changing an endpoint URI is a config change, not a code change — which matters in practice: in a Kubernetes deployment, that's a ConfigMap update and a rolling restart, with no rebuild.

## What you get

- **Compiler-enforced route topology** — sealed interfaces and pattern matching replace runtime-discovered routing mistakes with build failures.
- **Operationally configurable endpoints** — YAML bindings, validated against the code's sealed hierarchy at startup, with strict or permissive validation modes for prod vs. dev.
- **Zero hidden abstraction over Camel** — `RouteOutcome.body()` is set directly on the Camel exchange. No wrapper objects standing between you and Camel's actual behavior.
- **Full interoperability with Camel itself** — Guanaco routes and native Camel `RouteBuilder` routes can coexist in the same `CamelContext`. Adopt incrementally, or drop to raw Camel any time you need a feature Guanaco doesn't model.
- **EIPs as ordinary Java, not framework ceremony** — content-based routing, filtering, and multicast fall out naturally from sealed interfaces and return types. No EIP-specific DSL to learn for the common cases.

## Status

Guanaco is an early-stage prototype (v0.1). The core pipeline — scanning `@GuanacoRoute` processors, inspecting sealed-interface topology, validating YAML bindings, and generating Camel routes — works end to end. APIs are not yet stable and will change.

## Getting started

```xml
<dependency>
    <groupId>io.github.lilaschuda</groupId>
    <artifactId>guanaco</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
public class Application {
    public static void main(String[] args) throws Exception {
        GuanacoContext ctx = new GuanacoContext("com.example.myapp");
        ctx.wireRoutes();
        ctx.start();

        Runtime.getRuntime().addShutdownHook(new Thread(ctx::stop));
        Thread.currentThread().join();
    }
}
```

Guanaco scans the given base package for `@GuanacoRoute`-annotated processors, validates their declared sealed-interface outcomes against `routes.yaml`, and registers the resulting routes with the underlying `CamelContext`.

## Built on Apache Camel

Guanaco depends on and is built entirely on top of [Apache Camel](https://camel.apache.org/). All of Camel's components, connectors, and runtime behavior are available unchanged. See [NOTICE](./NOTICE) for attribution details.

## License

Apache License 2.0 — see [LICENSE](./LICENSE).
