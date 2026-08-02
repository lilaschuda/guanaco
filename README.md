# Guanaco

**Compiler-checked, idiomatic route definitions for Apache Camel — written entirely in native Java.**

Guanaco is a thin DSL layer on top of [Apache Camel](https://camel.apache.org/) that replaces Camel's fluent Java DSL and XML route definitions with plain Java: sealed interfaces, pattern matching, and records. Route topology is enforced by the compiler instead of discovered at runtime. Endpoint bindings live in a JSON or YAML file, so routes can be rewired in production — a ConfigMap update and a restart — without recompiling or redeploying new code.

Guanaco does not replace Camel's runtime, its component ecosystem, or its connectors. It sits on top of them.

> **Not affiliated with the Apache Software Foundation.** Guanaco is an independent, unofficial project built on top of Apache Camel. It is not endorsed by, sponsored by, or otherwise affiliated with the Apache Software Foundation. "Apache," "Camel," "Apache Camel," and the Apache feather logo are trademarks of The Apache Software Foundation.

## Why "Guanaco"?

The guanaco is a wild South American camelid — part of the same broader family as the camel, but from an entirely separate lineage, and the literal wild ancestor of the domesticated llama. The metaphor: Guanaco takes Camel's power and sets it loose. Same family, same strength, no harness — compiler-checked, idiomatic, unchained.

## The idea

A Camel route, in Guanaco, is a plain Java class implementing one method. The Camel `Exchange` is exposed directly — no wrapper abstraction stands between you and the full Camel API, so headers, properties, and every existing Camel idiom you already know are right there:

```java
@GuanacoRoute
public class OrderProcessor implements Processor<OrderRoute<?>> {

    @Override
    public OrderRoute<?> process(Exchange exchange) throws Exception {
        String body = exchange.getIn().getBody(String.class);
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

Where each outcome actually goes is configured separately, in `routes.yaml` (or `routes.json` — see [Configuration format](#configuration-format) below):

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

## EIPs beyond simple routing

Guanaco supports several Enterprise Integration Patterns as first-class, type-safe outcomes — no separate DSL to learn, no XML.

**Drop** — explicitly discards a message:

```java
if (body.isBlank()) return Drop.INSTANCE;
```

**Multicast** — fans a message out to multiple destinations at once. Best-effort by default: a failed send to one destination doesn't stop delivery to the rest, and failures are routed to the configured dead letter endpoint if one is set.

```java
return new Multicast(List.of(new ToInventory(order), new ToAuditLog(order)));
```

**Split** — decomposes one message into independent pieces, each dispatched on its own. Split items are matched to their endpoint purely by simple class name against `routes.yaml` bindings — deliberately **not** required to belong to the originating processor's own sealed hierarchy. This is what makes a cross-cutting outcome like `ToAuditLog` reusable across many unrelated processors: Java's sealed-type rules would otherwise force every such outcome into a single processor's own package. An optional Camel `AggregationStrategy` can be supplied to collect results using Camel's native splitter engine; without one, it's split-and-forget.

```java
return new Split(List.of(new ToMainframe(item1), new ToRest(item2), new ToAuditLog(item3)));
```

**Aggregate** — correlates and merges multiple incoming messages into one before your processor ever runs, declared as a config block rather than a return type (since a processor can't "decide" to aggregate — the merge happens before it's ever called):

```yaml
routes:
  OrderProcessor:
    from: kafka:orders
    aggregate:
      correlationHeader: orderId
      strategyRef: orderMergeStrategy
      completionSize: 10
      completionTimeoutMs: 5000
```

```java
guanacoContext.registerAggregationStrategy("orderMergeStrategy", new OrderMergeStrategy());
```

The merge strategy is a plain, compiled `org.apache.camel.AggregationStrategy` registered by name — no Spring bean lookup, no reflection. `correlationHeader` is resolved internally via Camel's type-safe `header(name)` builder, never an interpreted expression string.

### What Guanaco deliberately does not support

**Routing Slip** and **Dynamic Router** have no corresponding DSL token, and this is intentional rather than an oversight: Guanaco's route graphs are static once built, so that a route's shape never changes at runtime regardless of load. Runtime-computed, unbounded routing paths are out of scope by design.

**Scripting-language endpoints** (`language:groovy:...`, `language:js:...`, and similar) are actively rejected — `BindingValidator` checks every `from` and binding URI's scheme and refuses to boot if a scripting scheme is found. This preserves the same guarantee: what a route can do is fully determined by compiled code and static configuration, never by an interpreted script resolved at runtime.

**Recipient List** doesn't need a separate token: `Multicast`'s destination list is just whatever the processor computes, so a dynamically-built list of destinations is already fully supported — it's `Multicast`, not a distinct pattern, in Guanaco's model.

**Logging** has no framework-provided DSL either — there's nothing to provide, since a processor is a plain Java method body. Use SLF4J (or your logger of choice) directly inside your processor, exactly as you would in any other Java class.

## Closed-world dispatch

Every concrete `RouteOutcome` implementation in your base package is scanned exactly once at boot, into a frozen registry. `Split` and `Multicast` destinations are checked against this registry before dispatch, independent of whether they have a YAML binding — an outcome instance whose class was never part of that boot-time scan (wrong package, a construction mistake) is rejected and logged, rather than silently dispatched or silently dropped. No reflection, classpath scanning, or dynamic class loading happens anywhere in the per-message dispatch path; the registry is built once, at startup, and never touched again.

## What you get

- **Compiler-enforced route topology** — sealed interfaces and pattern matching replace runtime-discovered routing mistakes with build failures.
- **Operationally configurable endpoints** — bindings validated against the code's sealed hierarchy at startup, with strict or permissive validation modes for prod vs. dev.
- **Zero hidden abstraction over Camel** — `RouteOutcome.body()` is set directly on the Camel exchange. No wrapper objects standing between you and Camel's actual behavior.
- **Full interoperability with Camel itself** — Guanaco routes and native Camel `RouteBuilder` routes can coexist in the same `CamelContext`. Adopt incrementally, or drop to raw Camel any time you need a feature Guanaco doesn't model.
- **EIPs as ordinary Java, not framework ceremony** — content-based routing, filtering, multicast, split, and aggregation fall out naturally from sealed interfaces, return types, and thin config blocks. No EIP-specific DSL to learn for the common cases.
- **Deterministic by construction** — no dynamic routing graphs, no scripting endpoints, no runtime reflection in the dispatch path. What a route can do is fully knowable from its compiled code and its static configuration.

## Configuration format

Guanaco auto-detects YAML or JSON purely by file extension — there's no separate property to configure or keep in sync with the actual file. The default loader looks for `routes.json`, then `routes.yaml`, then `routes.yml`, in that order; **JSON always takes precedence if present**, on the assumption that a JSON file's presence is a deliberate choice. Both formats are parsed with strict duplicate-key detection — a repeated key at the same level fails configuration loading immediately rather than silently keeping whichever value was parsed last.

## Status

Guanaco is v0.2, pre-1.0. The core pipeline — scanning `@GuanacoRoute` processors, inspecting sealed-interface topology, validating bindings, and generating Camel routes — works end to end, along with Drop, Multicast, Split, and Aggregate. APIs are not yet stable and may change before v1.0.

## A note on the Spring dependency

Guanaco depends on `camel-spring-xml` and `spring-context` to support loading legacy Spring-based Camel XML routes alongside Guanaco-managed routes in the same context — this is what powers incremental migration from an existing Camel deployment. If you're starting a greenfield project with no existing Camel XML routes, these transitive dependencies are present but unused. A lighter-weight variant without Spring may be offered in a future version once there's clearer evidence of demand for it.

## Getting started

```xml
<dependency>
    <groupId>io.github.lilaschuda</groupId>
    <artifactId>guanaco</artifactId>
    <version>0.2.0-SNAPSHOT</version>
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

Guanaco scans the given base package for `@GuanacoRoute`-annotated processors, validates their declared sealed-interface outcomes against the configuration file `routes.json` or `routes.yaml`, and registers the resulting routes with the underlying `CamelContext`.

## Built on Apache Camel

Guanaco depends on and is built entirely on top of [Apache Camel](https://camel.apache.org/). All of Camel's components, connectors, and runtime behavior are available unchanged. See [NOTICE](./NOTICE) for attribution details.

## License

Apache License 2.0 — see [LICENSE](./LICENSE).
