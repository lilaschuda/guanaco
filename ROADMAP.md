# Roadmap

This document states what v1.0 actually covers, and what's deliberately deferred. It's separate from [CHANGELOG.md](./CHANGELOG.md), which records what's already shipped — this file is forward-looking only.

## v1.0 scope

v1.0 is the point where the public API is considered stable. Getting there means:

- Every EIP already listed below is implemented, validated, tested, and documented in the main README.
- The API surface listed in "v1.0 API freeze" below is locked — changes after v1.0 follow normal semantic versioning (breaking changes require a major version bump), rather than the "still finding the right shape" latitude pre-1.0 versions have had.
- Documentation debt from the `[Documentation Task]` backlog (see below) does not block v1.0 — it's ongoing, parallel work with no deadline.

## What's in scope for v1.0

Everything shipped through v0.8.0:

- **Core engine**: `@GuanacoRoute` processor scanning, sealed-interface topology inspection, binding validation (STRICT/PERMISSIVE/SILENT), route generation, `GuanacoContext` lifecycle, legacy Camel XML route coexistence.
- **Routing EIPs**: Drop, Multicast, Split (including cross-cutting outcome support via `RouteOutcomeRegistry`).
- **Message-stream EIPs**: Idempotent Consumer, Resequencer (STREAM and BATCH), Aggregate — with the fixed Idempotent → Resequence → Aggregate pipeline order.
- **Resiliency policies**: Throttler, Delayer, Circuit Breaker — with the fixed Throttle → Delay → Circuit Breaker binding order, and the hierarchical route-default/binding-override configuration model.
- **Configuration**: YAML/JSON auto-detection, strict duplicate-key checking, the scripting-scheme security guardrail.
- **Test support**: `GuanacoTestSupport`/`GuanacoRuntimeEnvironment`.

## v1.0 API freeze

The following are considered the public API and are locked at v1.0 — changes to any of these after v1.0 ships are breaking changes:

- `RouteOutcome<T>` and its contract (`body()`)
- `Processor<R>` (`process(Exchange)`)
- `Drop`, `Multicast`, `Split` and their construction APIs
- The `routes.yaml`/`routes.json` schema: `bindings` (both short-form string and long-form `BindingTarget` shapes), `errorHandler`, `aggregate`, `idempotent`, `resequence`, `throttler`, `delayer`, `circuitBreaker`, and their nested fields
- `GuanacoContext`'s public methods: `wireRoutes()`, `start()`, `stop()`, `registerAggregationStrategy(...)`, `registerDelayStrategy(...)`, `loadConfig()` (protected, overridable)
- `GuanacoAggregateConfig`, `GuanacoIdempotentConfig`, `GuanacoResequenceConfig`, `GuanacoThrottlerConfig`, `GuanacoDelayerConfig`, `GuanacoCircuitBreakerConfig`, `BindingTarget`, `GuanacoRuntimeContext`
- `GuanacoDelayStrategy`
- `GuanacoTestSupport`, `GuanacoRuntimeEnvironment`
- The fixed pipeline orderings themselves (Idempotent → Resequence → Aggregate; Throttle → Delay → Circuit Breaker) — these are behavioral guarantees, not just API shape, and are part of what v1.0 promises to keep stable

Internal classes not listed here (`GuanacoRouteBuilder`, `TopologyInspector`, `BindingValidator`, `RouteOutcomeRegistry`, `GuanacoResilienceHelper`, `LoggingIdempotentRepository`, `GuanacoDelegatingAggregationStrategy`) are implementation details and may change without a major version bump, even though some are technically public classes today.

## Explicitly deferred to v1.1 and beyond

These were identified early in the EIP-coverage review and deliberately scoped out of v1.0, to keep the initial stable release focused rather than open-ended:

- **ControlBus** — programmatic route lifecycle management (start/stop/status of routes at runtime)
- **Wire Tap** — async, non-blocking copies of a message sent to a diagnostic path
- **Message History** — tracking a message's route through the system via a header
- **Saga** — distributed transactions with compensating actions
- **Sampling** — discarding a fraction of throughput above a threshold
- **Threads** — explicit thread-pool sizing/concurrency tuning on `RouteConfig`

None of these have a design conversation started yet. When one is prioritized, it gets the same treatment every other EIP in this project has had: a design discussion resolving its real forks before any code is written.

## Deliberate, permanent non-goals

Not deferred — never planned, and documented as intentional in the main README already:

- **Routing Slip** and **Dynamic Router** — no DSL surface for either exists, and none is planned. Guanaco's route graphs are static once built; runtime-computed, unbounded routing paths are out of scope by design.
- **Scripting-language endpoints** — actively rejected at boot by `BindingValidator`'s scheme guardrail, not just unsupported.
- **A separate logging DSL** — a processor is a plain Java method; use SLF4J directly.
- **Recipient List** as a distinct feature** — already fully covered by `Multicast`, since a processor can compute its destination list dynamically.

## Ongoing, unscheduled documentation work

The following EIP-pattern documentation entries remain open, tracked as background work with no version tied to them — they don't block v1.0 and aren't scheduled into any specific release:

Message Channel, Message, Message Translator, Point-to-Point Channel, Publish-Subscribe Channel, Change Data Capture, Correlation Identifier, Message Expiration, Scatter-Gather, Process Manager, Message Broker, Load Balancer, Service Call, Kamelet, Content Enricher, Content Filter, Claim Check, Normalizer, Sort, Validate, Messaging Mapper, Event-Driven Consumer, Polling Consumer, Competing Consumers, Selective Consumer, Durable Subscriber, Resumable Consumer, Transactional Client.
