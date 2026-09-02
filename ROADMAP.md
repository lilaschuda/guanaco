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

- `@GuanacoRoute`, `RouteOutcome<T>` and its contract (`body()`)
- `Processor<R>` (`process(Exchange)`)
- `Drop`, `Multicast`, `Split` and their construction APIs
- `GuanacoDelayStrategy`
- The `routes.yaml`/`routes.json` schema and its backing types: `GuanacoConfig` (including nested `FrameworkConfig` and `ValidationMode`), `RouteConfig` (including nested `ErrorHandlerConfig`), `BindingTarget`, `GuanacoAggregateConfig`, `GuanacoIdempotentConfig`, `GuanacoResequenceConfig`, `GuanacoThrottlerConfig`, `GuanacoDelayerConfig`, `GuanacoCircuitBreakerConfig`, and all their nested fields
- `ConfigLoader`'s current two-method surface: `load()` (default classpath resolution) and `load(String)` (explicit single classpath resource). See "Explicitly deferred" below for planned multi-file loading — additive, won't change this signature.
- `GuanacoContext`'s public surface: the constructor (`GuanacoContext(String basePackage)`), `wireRoutes()`, `loadLegacyXmlRoutes(String)`, `registerAggregationStrategy(...)`, `registerDelayStrategy(...)`, `loadConfig()` (protected, overridable). `start()`/`stop()` are inherited from `SpringCamelContext` — Guanaco freezes its own usage contract around them (that they exist and behave like normal Camel lifecycle methods), but their exact signatures track upstream Spring/Camel versions, not something Guanaco can unilaterally guarantee.
- The exception hierarchy: `context.exception.{BindingValidationException, ForbiddenComponentException, GuanacoInspectionException, GuanacoRouteBuilderException, InvalidRouteConfigurationException}` and `config.exception.{GuanacoConfigException, UnsupportedConfigFormatException}` — their existence, package, and constructor signatures
- `GuanacoTestSupport`, `GuanacoRuntimeEnvironment`
- The fixed pipeline orderings themselves (Idempotent → Resequence → Aggregate; Throttle → Delay → Circuit Breaker) — these are behavioral guarantees, not just API shape, and are part of what v1.0 promises to keep stable

Internal classes not listed here (`GuanacoRouteBuilder`, `TopologyInspector`, `BindingValidator`, `RouteOutcomeRegistry`, `GuanacoResilienceHelper`, `LoggingIdempotentRepository`, `GuanacoDelegatingAggregationStrategy`, `GuanacoRuntimeContext`) are implementation details and may change without a major version bump. These are genuinely package-private as of v1.0, not just documented-as-internal by convention.

**Not covered by the v1.0 freeze:** `api.telemetry` (`GuanacoTelemetryListener`, `GuanacoMicrometerListener`). This package ships in the v1.0 jar but is unwired from the engine — no route-building code emits these events yet — so its method signatures may still change ahead of stabilization, without that being treated as a breaking change. See the package's own Javadoc.

## Shipped since v1.0

Wire Tap, Sampling, Threads, ControlBus, Message History, and Saga — all originally listed below as "explicitly deferred" — shipped in v1.1.0. See [CHANGELOG.md](./CHANGELOG.md) for what each one covers, and [CAMEL_INTERNALS.md](./CAMEL_INTERNALS.md) for the Camel-internal behaviors their implementations depend on.

Two deliberate, known gaps remain from that work, not yet scheduled:

- **Wire Tap's tap-copy success path has no telemetry hook** — only tap *failure* is reported, via `onOutcomeFailed`. Building a success hook would need genuinely new DSL wiring on the tap copy's own completion, not reuse of anything that exists today.
- **Cross-route `routeId` existence validation** — e.g. verifying a ControlBus `controlbus:route?routeId=X` binding's `routeId` refers to a route that will actually exist. `BindingValidator` currently only has per-processor, local visibility; this needs a genuine topology/route-graph validation pass with visibility across the whole app, which would also be the natural place to add `direct:`/`seda:` cross-reference checks. Not started.

## Explicitly deferred to v1.2 and beyond

- **Multi-file / modular config loading** — `ConfigLoader` loading a collection of config files rather than a single named resource. A single custom filename has little value on its own; the real use case is splitting config across multiple files. One core design question is resolved: on a duplicate binding across files, fail loudly at boot with no precedence assumptions — position within or across files never matters (matching the project's existing `STRICT_DUPLICATE_DETECTION` philosophy). The pre-existing JSON-over-YAML precedence rule for a single logical config carries over unchanged, as a separate, orthogonal rule. Still genuinely undecided: where `framework:`/`validation:` live when config is split across files, and whether file ordering matters at all. Implementation has not started.

When a v1.2 item is prioritized, it gets the same treatment every other EIP in this project has had: a design discussion resolving its real forks before any code is written.

## Deliberate, permanent non-goals

Not deferred — never planned, and documented as intentional in the main README already:

- **Routing Slip** and **Dynamic Router** — no DSL surface for either exists, and none is planned. Guanaco's route graphs are static once built; runtime-computed, unbounded routing paths are out of scope by design.
- **Scripting-language endpoints** — actively rejected at boot by `BindingValidator`'s scheme guardrail, not just unsupported.
- **A separate logging DSL** — a processor is a plain Java method; use SLF4J directly.
- **Recipient List** as a distinct feature** — already fully covered by `Multicast`, since a processor can compute its destination list dynamically.

## Ongoing, unscheduled documentation work

The following EIP-pattern documentation entries remain open, tracked as background work with no version tied to them — they don't block v1.0 and aren't scheduled into any specific release:

Message Channel, Message, Message Translator, Point-to-Point Channel, Publish-Subscribe Channel, Change Data Capture, Correlation Identifier, Message Expiration, Scatter-Gather, Process Manager, Message Broker, Load Balancer, Service Call, Kamelet, Content Enricher, Content Filter, Claim Check, Normalizer, Sort, Validate, Messaging Mapper, Event-Driven Consumer, Polling Consumer, Competing Consumers, Selective Consumer, Durable Subscriber, Resumable Consumer, Transactional Client.
