# Changelog

All notable changes to Guanaco are documented here. Versions before v1.0.0
were `-SNAPSHOT` and not published as tagged releases. From v1.0.0 onward,
this project follows Semantic Versioning: the public API and behavioral
guarantees documented in `ROADMAP.md`'s "v1.0 API freeze" section will not
change without a major version bump.

## [1.2.0] - in progress

### Added
- **`guanaco-kotlin` module** (new, optional Maven module — `guanaco-core` never
  depends on it, matching `guanaco-telemetry`'s existing opt-in precedent):
  - **`AsyncOutcomeProcessor<R>` + `OutcomeCallback<R>`**, new in `guanaco-core`'s
    `api` package: a coroutine-agnostic alternative to `Processor<R>` for route
    logic that needs to await something without blocking the calling thread. A
    `@GuanacoRoute` class implements exactly one of the two, never both —
    implementing both is now rejected at boot (`TopologyInspector`), since which
    one would silently win previously depended on unguaranteed JVM reflection
    ordering. Dispatch is wired as a genuine Camel `AsyncProcessor`
    (`GuanacoRouteBuilder.AsyncDispatchStep`), composed through Camel's own
    `ReactiveExecutor`/`Pipeline` machinery the same way every other EIP step
    already is — confirmed by tracing `ChoiceProcessor`, `WireTapProcessor`,
    `SendProcessor`, and Camel's own `.saga()` processor, all the way down to
    `AsyncProcessorSupport`, so an async-dispatched route is non-blocking
    end-to-end, not just at the one step. `dispatchOutcome`'s existing
    Drop/Split/Multicast/WireTap/SagaStep handling is now shared, unchanged,
    between the sync and async paths via an extracted `finishDispatch`.
  - **`SuspendOutcomeProcessor<R>`**, the Kotlin bridge: a `@GuanacoRoute` class
    extends it and implements a suspending `processSuspending(Exchange): R`
    instead of `Processor`'s synchronous method. `TopologyInspector` now also
    checks a processor class's immediate superclass (`getGenericSuperclass()`),
    not just its directly-implemented interfaces, since a class extending this
    bridge never implements `AsyncOutcomeProcessor` directly itself.
  - **`GuanacoCoroutineScopeService`**: one `CoroutineScope` per `CamelContext`,
    found-or-created lazily (double-checked-locked, stress-tested under 64-way
    concurrent contention) via `CamelContext.hasService`/`addService` — no
    dependency injection needed despite `@GuanacoRoute` classes being
    instantiated via a no-arg reflection constructor. Implements plain
    `org.apache.camel.Service`, so its lifecycle is tied to the owning
    context's automatically; cancellation on context stop is reported via
    `OutcomeCallback.onFailure(...)` before being rethrown, satisfying Camel's
    `ShutdownStrategy` inflight-exchange tracking while preserving correct
    Kotlin coroutine-cancellation hygiene.
  - **Kotlin ergonomics**: named/default-argument factory functions for
    `Multicast`, `WireTap<T>`, and `SagaStep<T>` (closing the gap where
    Kotlin's named-argument calling convention doesn't reach across the Java
    interop boundary onto the framework's own constructors); reified generic
    helpers `RouteOutcome<*>.bodyAs<T>()` and `Exchange.bodyAs<T>()`.
  - Comprehensive test coverage for the whole thread: dispatch parity between
    the sync and async paths (plain outcomes, `Drop`, `WireTap`), a real
    thread-pool-exhaustion proof that async dispatch genuinely releases a
    `.threads()` pool's thread rather than merely appearing non-blocking,
    defensive handling of a misbehaving `AsyncOutcomeProcessor` that throws
    instead of calling its callback, the classpath-scan path itself (not just
    direct construction), and the Kotlin bridge exercised against the real
    `kotlinx-coroutines-core` library (real `delay()`/dispatcher hops, and
    context-stop cancellation of an in-flight suspend function).
- **Nullability annotations (JSpecify)** across `api`, `api.telemetry`,
  `context`, `context.exception`, `config`, `config.exception`, and
  `testutils` — `@NullMarked` packages with `@Nullable` applied to every
  field, parameter, and return type actually capable of being null, verified
  case by case rather than applied uniformly (e.g. `Multicast`/`Split`'s
  `body()` is genuinely non-null; `WireTap`/`SagaStep`'s is not, since both
  delegate to a wrapped outcome that may itself be `Drop`).

### Fixed
- **Two latent NPE risks surfaced by the nullability audit**, both from a
  declared field default silently not protecting against an explicit `null`
  through an unguarded setter: `GuanacoConfig.setFramework(...)` and
  `FrameworkConfig.setValidation(...)` now coalesce an explicit `null` back
  to their documented default instead of storing it — closing a real gap
  where `config.getFramework().getValidation()`, called with no null-check
  in both `ConfigLoader.load()` and `GuanacoContext.wireRoutes()`, could have
  NPE'd on a literal `framework: null` in routes.yaml/json.
  `GuanacoTestSupport.withValidation(null)` — a second, reachable path into
  the same gap — now coalesces the same way.

### Scope
- Multi-file/modular config loading remains deferred, as noted since
  `0.11.0` — implementation still hasn't started; its two open design
  questions (where `framework:`/`validation:` live when config is split
  across files, and whether file ordering matters) are unchanged.

## [1.1.0] - 2026-09-02

### Added
- **Telemetry engine wiring**: Instrumented Idempotent, Resequence, Aggregate,
  Delayer, and Dispatch operations, with optional boot-time short-circuiting
  hooks in `GuanacoRouteBuilder` so an unregistered listener costs nothing.
  `GuanacoResilienceHelper` reports circuit-breaker timing/failures with the
  real R4J exception re-thrown afterward. Cleaned up the
  `registerTelemetryListener` API on `GuanacoContext` and its Javadoc.
- **Wire-Tap Routing**: Support for asynchronous wire-tapping with isolated 
  execution and telemetry logging.
- **Wire-Tap Test Suite**: Unit tests verifying primary message delivery, 
  failure logging, and DLQ non-contamination under failure scenarios.
- **Sampling EIP**: Route-level ingress sampling (`RouteConfig.sample`) and
  independent binding-level egress sampling (`BindingTarget.sample`) via
  `GuanacoSampleConfig` (`messageFrequency` or `samplePeriodMillis`,
  mutually exclusive). Fixed as the first pipeline stage at route level.
- **Threads EIP**: Route-level pipeline thread handoff via
  `GuanacoThreadsConfig` — an inline Camel-managed pool
  (`poolSize`/`maxPoolSize`/`threadName`/`rejectedPolicy`/`callerRunsWhenRejected`)
  or a named, shared pool via `executorServiceRef`, resolved against the
  existing Spring `ApplicationContext`. Runs after Sample, before Idempotent.
- **ControlBus support**: `controlbus:route?routeId=...&action=...` is now a
  fully supported binding target for route lifecycle management
  (start/stop/suspend/resume/status) — dispatched through the existing
  binding model, no new outcome type or config schema required.
- **Message History**: Route-level telemetry extension capturing the full
  per-node execution path (via Camel's native message history mechanism)
  through a new `onMessageHistory` hook on `GuanacoTelemetryListener`,
  reported through a single `onCompletion()` handler so it uniformly
  covers dispatch success, dispatch failure, Drop, Sample-rejection, and
  resequence/idempotent short-circuits alike.
- **Saga EIP**: `SagaStep<T>` outcome wrapper (wraps a primary outcome plus
  a per-message snapshot of exchange state for compensation/completion
  callbacks) and route-level `GuanacoSagaConfig` (compensation/completion
  outcome bindings, propagation, completion mode, timeout, and an optional
  named/shared saga service). A Saga-configured route compiles to two
  internal Camel routes connected by a `direct:` hop, guaranteeing outcome
  dispatch and option snapshotting complete before Camel's own saga
  coordinator begins tracking the exchange. `GuanacoContext` registers a
  default in-memory saga service automatically; real distributed
  coordination (e.g. LRA) only requires registering a `CamelSagaService`
  Spring bean and pointing `sagaServiceRef` at it.

### Changed
- Route-level fixed pipeline order extended to
  `Sample → Threads → Idempotent → Resequence → Aggregate → dispatch`.
- Route-level Saga configuration adds a second, internal Camel route per
  processor (`guanaco-<name>-saga`, connected via an internal `direct:`
  hop) — visible in startup logs and in `MessageHistory` route IDs for
  saga-participating processors.

### Fixed
- **Wire-Tap DLQ Leak**: Handled background wire-tap exceptions (`.handled(true)`) to isolate 
  side-channel failures from the main route's Dead Letter Channel.
- **Simple Expression Property Lookup**: Switched to bracket notation `${exchangeProperty[...]}` 
  in dynamic URIs to prevent OGNL method navigation conflicts.
- **DSL Choice Block Nesting**: Explicitly closed choice definitions with `.end()` 
  in `GuanacoRouteBuilder` to prevent route hierarchy ambiguities.
- **ControlBus scripting-scheme gap**: the existing forbidden-scheme guardrail
  in `BindingValidator` matched only the top-level component scheme, so
  `controlbus:language:...` (arbitrary expression execution against the
  CamelContext) previously passed unblocked. Now explicitly rejected;
  `controlbus:route` URIs are also required to carry a recognized `action`
  (`start`/`stop`/`suspend`/`resume`/`status`) at boot, since a missing
  action otherwise silently no-ops at runtime.

## [1.0.0]

First stable, tagged release. Everything from `0.1.0` through `0.11.0` was
pre-release iteration; this release freezes the public API and behavioral
guarantees exactly as documented in `ROADMAP.md`'s "v1.0 API freeze"
section — the sealed `RouteOutcome`/`Processor`/`Drop`/`Multicast`/`Split`
contracts, the `routes.yaml`/`routes.json` schema and its backing config
types, `GuanacoContext`'s public lifecycle, the full exception hierarchy,
the test-support API, and the fixed EIP pipeline orderings. `api.telemetry`
ships in this release but is explicitly excluded from the freeze — see its
package documentation.

### Fixed
- README's Maven dependency snippet still referenced `0.6.0-SNAPSHOT`, a
  coordinate that was never actually published (pre-1.0 SNAPSHOTs weren't
  tagged releases). Updated to `1.0.0`.

## [0.11.0]

Phase 2 of the v1.0 release process: auditing `ROADMAP.md`'s API freeze
list against the actual current code, plus two small fixes surfaced along
the way.

### Fixed
- `GuanacoContext`'s constructor now sets a default, empty
  `StaticApplicationContext` — `SpringCamelContext` requires one to be
  present before `wireRoutes()`/`start()` regardless of whether legacy XML
  coexistence is used, and the README's own canonical usage example didn't
  set one, so it would have NPE'd for any user following it verbatim.
  Still fully overridable via `setApplicationContext(...)` before
  `wireRoutes()` for real Spring bean integration.
- Removed stray `[cite: N]` citation artifacts (120 occurrences across 9
  files in `context`) left over from doc generation, previously visible in
  the published Javadoc.

### Changed
- **API freeze audit (`ROADMAP.md`)**: corrected against the real current
  code rather than the original v1.0 plan. Removed `GuanacoRuntimeContext`
  from the frozen list (confirmed package-private, never publicly exposed).
  Added everything genuinely public that was missing from the list: the
  full exception hierarchy (`context.exception.*`, `config.exception.*`),
  `@GuanacoRoute`, `GuanacoContext`'s constructor and
  `loadLegacyXmlRoutes(String)`, `RouteConfig`/`ErrorHandlerConfig`,
  `GuanacoConfig`/`FrameworkConfig`/`ValidationMode`, and `ConfigLoader`'s
  `load()`/`load(String)`. Corrected the internal-classes caveat to reflect
  that those classes are now genuinely package-private, not just
  documented-as-internal by convention. Documented `api.telemetry`'s
  exclusion from the freeze at the roadmap level (previously only in the
  package's own Javadoc). Added multi-file/modular config loading to the
  explicitly-deferred list, with its open design questions noted.
- Decided and documented: the `GuanacoContext extends SpringCamelContext`
  coupling is deliberately left as-is for v1.0. Spring integration is a
  real feature in its own right (custom bean access), not just a migration
  bridge, so there's no natural point to drop it. Any decoupling is
  deferred to a v2.0 major-version discussion informed by real user
  feedback — v2.0 is not required to stay backward-compatible with v1.x.

## [0.10.0]

### Changed
- **Public API Separation**: Re-architected the package structure to expose 
  explicit developer-facing interfaces under `io.github.lilaschuda.guanaco.api`,
  establishing a clean boundary between framework extensions and core runtime internals.
- **Access Tightening**: Reduced class visibility of internal helpers, route builders
  and pipeline infrastructure to package-private to prevent unintended coupling and misuse.

### Documentation
- **Javadoc Overhaul**: Added missing descriptions, parameter tags (`@param`), return tags (`@return`)
   and constructor documentation across all core packages (`config`, `context.exception`, `testutils`).
- **DocLint Compliance**: Resolved 100+ build-time Javadoc warnings, achieving a 
  completely clean compilation output under JDK strict inspection mode.

## [0.9.0]

A review-and-refactor pass, no new EIPs — closing structural debt that had
accumulated across six EIPs' worth of reactive, per-turn additions before
the v1.0 API freeze locks the shape in.

### Fixed
- **Real bug**: a binding with only a `delayer` configured — no `throttler`,
  no `circuitBreaker` — was wired incorrectly. `addBranch`'s inline dispatch
  logic never handled that specific combination, so `.to(uri)` was attached
  to the wrong parent definition (bypassing the delay entirely) instead of
  the `DelayDefinition` itself. The already-written but never-called
  `attachPlainTo(...)` helper — which correctly handles all three possible
  parent shapes — is now actually used for every combination, rather than
  duplicated, incomplete inline logic. Caught by a new regression test;
  this combination was never exercised by any prior test or the
  `04-resiliency-pipeline` demo, whose `ToPartner` binding always had all
  three policies together.
- `applyDelay`'s missing-`delayStrategyRef` failure now throws
  `GuanacoRouteBuilderException`, matching `wireAggregate`'s equivalent
  missing-`strategyRef` failure — previously threw `GuanacoInspectionException`,
  a different type for the structurally identical situation.
- Closed an encapsulation leak: `GuanacoContext.getDelayStrategies()`
  exposed the live, mutable strategy map directly, letting
  `GuanacoTestSupport` bypass `registerDelayStrategy`'s null/blank-name and
  duplicate-registration guards entirely. Removed; `GuanacoTestSupport` now
  goes through `registerDelayStrategy`/`registerAggregationStrategy` like
  every other caller.
- Removed a dead, unused `GuanacoContext(String, ApplicationContext)`
  constructor overload — added for a test-setup need that was fixed a
  different way before this overload was ever actually used anywhere.

### Changed
- **`GuanacoRuntimeContext`**, a new record bundling `RouteOutcomeRegistry`,
  the `AggregationStrategy` map, and the `GuanacoDelayStrategy` map — the
  boot-time-global state shared by every route built in one `wireRoutes()`
  pass, as distinct from a route's own per-route specifics. Replaces three
  separate constructor parameters on `GuanacoRouteBuilder` (which had grown
  to seven total, one added reactively per EIP) with one. A future EIP
  needing its own named, boot-time registry adds a field to this record,
  not a new `GuanacoRouteBuilder` constructor parameter — this is the
  intended extension point, chosen deliberately ahead of the v1.0
  constructor freeze in `ROADMAP.md`.
- `GuanacoTestSupport` gained `withRouteAggregate(...)`,
  `withRouteIdempotent(...)`, `withRouteResequence(...)`, and
  `registerAggregationStrategy(...)` — closing a real gap where the three
  Tier 1 message-stream EIPs had no test-support hooks at all, unlike the
  three Tier 2 resiliency policies. Every hook (old and new) now has an
  end-to-end test proving it actually wires through, not just that the
  field exists.

`GuanacoRouteBuilderTestSupport`'s `registerRoute(...)` overloads keep
their existing signatures unchanged — internally rebuilt to construct a
`GuanacoRuntimeContext` rather than passing three maps, so no existing
test call site needed to change.

## [0.8.0]
### Fixed(core): resolve delayer dispatch wiring, unify strategy exceptions, and secure context encapsulation

- Fix delayer-only binding wiring by delegating dispatch directly to attachPlainTo, 
  ensuring standalone delay blocks nest correctly instead of creating dangling routes.
- Consolidate missing strategy reference exceptions on GuanacoRouteBuilderException 
  for consistent route-building error handling.
- Eliminate encapsulation leak in GuanacoContext by removing getDelayStrategies() 
  and updating GuanacoTestSupport to use registerDelayStrategy(...).

## [0.7.0]

### Added
- `waitDurationInOpenStateMs` on `GuanacoCircuitBreakerConfig`
  controls how long Resilience4j keeps the circuit open before probing again.
  Surfaced specifically to make circuit breaker recovery observable in a
  reasonable timeframe (rather than Resilience4j's own 60s default), and
  as a generally useful extension point for custom recovery timing.
- **Delayer EIP**, completing the three dispatch-wrapping resiliency
  policies (alongside Circuit Breaker and Throttler from v0.5.0/v0.6.0),
  sharing the identical route-default/binding-override hierarchy.
- **`GuanacoDelayStrategy`** — a compiled, per-exchange delay computation
  interface, registered by name via `GuanacoContext.registerDelayStrategy(...)`.
  Exists specifically so a computed delay (e.g. exponential backoff based
  on a retry-count header) has a type-safe source, the same way
  `AggregationStrategy`/`strategyRef` already works for Aggregate — no
  Spring bean lookup, no reflection, no interpreted expression string.
  A `delayer` block sets exactly one of `delayMs` (a fixed constant) or
  `delayStrategyRef` (a registered `GuanacoDelayStrategy`) — these are
  alternative sources for the same single value, not independent
  conditions, so exactly one, not "at least one," is required.
- **Fixed, non-configurable three-layer ordering** when Throttler, Delayer,
  and Circuit Breaker all apply to the same binding: Throttle (outermost)
  → Delay → Circuit Breaker (innermost). Delay sits between the two
  deliberately — nesting it inside Circuit Breaker would count the
  artificial pause toward the circuit breaker's own timeout measurement,
  which could trip the breaker purely because of Guanaco's own injected
  delay rather than genuine downstream latency.
- `asyncDelayed` on `delayer` defaults to `false`, matching Camel's own
  native default (blocking) rather than silently overriding it — a large
  `delayMs` with `asyncDelayed` left unset will block the calling route
  thread for the full duration. Documented explicitly as a footgun to be
  aware of, not silently guarded against.
- `GuanacoTestSupport.registerDelayStrategy(...)` and `.withRouteDelayer(...)`,
  completing the same test-support surface already available for Throttler
  and Circuit Breaker.

This completes Tier 2 of the roadmap (Circuit Breaker, Throttler, Delayer) —
all three dispatch-wrapping resiliency policies identified during the
v0.5.0 schema design conversation are now implemented, validated, and
tested, including their three-way interaction when combined on a single
binding.

## [0.6.0]

### Added
- **Throttler EIP**, reusing the route-default/binding-override hierarchy
  introduced for Circuit Breaker in v0.5.0. Supports `requestsPerPeriod`,
  `timePeriodMillis`, `asyncDelayed`, and `rejectExecution` —
  `asyncDelayed` and `rejectExecution` are rejected at boot if both are
  true, since "never wait" and "wait without blocking" are contradictory.
  A message rejected via `rejectExecution: true` propagates through the
  route's normal error handling, the same as any other exception.
- **Fixed, non-configurable ordering** when both Throttler and Circuit
  Breaker apply to the same binding: Throttle always wraps outermost
  (admission control before an attempt), Circuit Breaker innermost
  (failure detection on the attempt itself).
- **`GuanacoContext.loadConfig()`** extracted as a small, protected,
  overridable hook around what was previously an inline `configLoader.load()`
  call inside `wireRoutes()` — lets test support code inject route
  configurations built programmatically, bypassing a physical config file
  entirely, without touching any other part of `wireRoutes()`'s pipeline.
- **`GuanacoTestSupport` / `GuanacoRuntimeEnvironment`**, a public test
  utility for applications building on Guanaco: builds and starts routes
  programmatically (`.route(...)`, `.withRouteThrottler(...)`,
  `.withRouteCircuitBreaker(...)`, `.withValidation(...)`), and provides
  simplified mock-endpoint and message-sending helpers for assertions.

### Changed
- `validateCircuitBreakerScope` generalized into `validateDslOnlyPolicyScope`,
  covering both `circuitBreaker` and `throttler` in one shared check rather
  than near-duplicate methods that could drift out of sync — rejects at
  boot any per-binding override declared on an outcome that isn't a
  permitted subtype of the processor's sealed hierarchy, since such an
  outcome is only ever reachable via Multicast/Split's imperative dispatch.

### Fixed
- An `enabled: false` per-binding policy override no longer fails
  structural completeness validation — its other fields
  (`requestsPerPeriod`, `timePeriodMillis`, etc.) are meaningless once the
  policy is disabled, so they're no longer required to be populated.
- `GuanacoTestSupport` was missing the `ApplicationContext` setup
  `GuanacoContext` (as a `SpringCamelContext` subclass) requires before
  `start()` — every test using it would fail with a `NullPointerException`
  from Spring's internals. Now sets a `StaticApplicationContext`, matching
  the convention already established in `GuanacoContextTest`.

## [0.5.0]

### Added
- **Hierarchical binding policy resolution.** `bindings` now accepts, per
  outcome, either a plain URI string (short form, inherits route-level
  defaults) or a rich object with `uri` and per-target policy overrides
  (long form) — singly or as a list of either. Backed by `BindingTarget`
  and a custom Jackson deserializer (`BindingsDeserializer`) normalizing
  both shapes into `Map<String, List<BindingTarget>>`.
- **Circuit Breaker EIP**, as the first policy supporting this hierarchy:
  a route-level `circuitBreaker:` block sets the default for every binding
  on that route; a binding-level `circuitBreaker:` override replaces it for
  that one target; `enabled: false` on a binding opts out of an inherited
  route-level policy entirely. Wired via `GuanacoResilienceHelper`, using
  Camel's `CircuitBreakerDefinition`/`Resilience4jConfigurationDefinition`
  plain-setter API rather than a long fluent chain — deliberately chosen
  after several fluent-chain generics mismatches elsewhere in this project
  made the setter-based approach the more reliable one to build against.
- **Boot-time scope guardrail:** a per-binding `circuitBreaker` override on
  an outcome that isn't a permitted subtype of the processor's sealed
  hierarchy is rejected at startup — such an outcome is only ever reachable
  via Multicast/Split's `producerTemplate.send()` path, which has no Camel
  DSL node for a circuit breaker to wrap. Documented residual limitation:
  a sealed-hierarchy outcome *also* emitted via Multicast/Split by
  developer code will silently not get the circuit breaker on that path —
  this specific case isn't statically decidable.

### Scope
- Throttler and Delayer are deferred to their own subsequent versions,
  reusing this same route-level-default / binding-level-override pattern
  now that it's implemented and tested against a real EIP.

## [0.4.0]

### Added
- **Resequencer EIP.** A `resequence:` block on `RouteConfig` reorders
  incoming messages by a configured `sequenceHeader`, resolved via Camel's
  type-safe `header(name)` builder. Supports both of Camel's resequencing
  modes:
  - **STREAM** — a sliding window that releases as ordering allows;
    `capacity` (default 1000) and `timeoutMs` (default 1000ms) both
    optional with sensible defaults. `rejectOld` (default `true`) rejects
    a message older than the last released sequence number rather than
    waiting for it indefinitely.
  - **BATCH** — collects a full batch, sorts it completely, then releases
    it as one sorted unit. Requires at least one of `capacity`/`timeoutMs`,
    matching Aggregate's completion-condition validation shape.
- **Fixed, non-configurable three-stage pipeline order** when Idempotent,
  Resequence, and Aggregate are all configured on one route: Idempotent
  Consumer runs first (drop cheap duplicates before allocating sequence
  buffer memory), then Resequence (so an order-sensitive
  `AggregationStrategy` always receives messages in strict sequence), then
  Aggregate.
- `rejectOld` validation: rejected at boot if set alongside BATCH mode,
  since it has no meaning there and its presence most likely signals a
  typo in `mode`.

### Fixed
- `ResequenceDefinition.rejectOld()` is a no-argument toggle in the Camel
  version this project targets, not a boolean setter — corrected the
  wiring to call it conditionally rather than pass a boolean argument.

This completes Tier 1 of the roadmap (Aggregate, Idempotent Consumer,
Resequencer) — all three "natural engine extension" EIPs identified early
in the v0.2 planning are now implemented, validated, and tested, including
their interaction when combined on a single route.

## [0.3.0]

### Added
- **Configuration format auto-detection.** `ConfigLoader` now supports both
  YAML and JSON, detected purely by file extension — `routes.json` takes
  precedence over `routes.yaml`/`routes.yml` if both are present. No new
  property to configure or keep in sync with the actual file.
- **Strict duplicate-key detection** in configuration files, for both YAML
  and JSON. A repeated key at the same level now fails configuration loading
  immediately, rather than silently keeping whichever value was parsed last.
- **Aggregate EIP.** A `aggregate:` block on `RouteConfig` correlates and
  merges multiple incoming messages into one before a processor runs, using
  a plain, compiled `org.apache.camel.AggregationStrategy` registered by
  name via `GuanacoContext.registerAggregationStrategy(...)` — no Spring
  bean lookup, no reflection. Correlation is resolved via Camel's type-safe
  `header(name)` builder, never an interpreted expression string.
- **Idempotent Consumer EIP.** An `idempotent:` block filters duplicate
  messages, by a configured `messageIdHeader`, before they ever reach a
  processor. In-memory only (`MemoryIdempotentRepository`) in this version,
  wrapped in `LoggingIdempotentRepository` so a filtered duplicate is always
  logged regardless of Camel's own internal logging configuration.
- **Fixed, non-configurable ordering** when both `idempotent:` and
  `aggregate:` are declared on the same route: idempotent filtering always
  wraps outermost, so a duplicate can never be counted toward an
  aggregation group's completion.
- **Scripting component guardrail.** `BindingValidator` now rejects any
  `from` or binding endpoint URI whose scheme matches a known scripting
  component (`language`, `groovy`, `js`, `javascript`, `mvel`, `ognl`,
  `python`), matched by URI scheme only — never substring containment — so
  a legitimate URI like `kafka:python:events` is never mistakenly flagged.

### Changed
- README rewritten to reflect the current `Processor<R>` signature
  (`Exchange`, not a `GuanacoMessage` wrapper — that abstraction was never
  built), and to document Split, Aggregate, the Script guardrail,
  closed-world dispatch, and JSON/YAML configuration.
- README now explicitly documents Guanaco's Spring dependency
  (`camel-spring-xml`, `spring-context`) and why it exists — legacy XML
  route compatibility — rather than leaving it undocumented.
- Routing Slip, Dynamic Router, and Log are now documented as deliberate
  architectural omissions rather than left as unaddressed roadmap items —
  none of the three ever had a DSL surface to misuse in the first place.
- Recipient List is documented as already fully covered by `Multicast`,
  since nothing prevents a processor from computing its destination list
  dynamically — no separate implementation needed.

## [0.2.0]

### Added
- `RouteOutcomeRegistry` — a frozen, boot-time-only, package-bounded scan of
  every concrete `RouteOutcome` implementation. `Split` and `Multicast`
  destinations are checked against this registry before dispatch,
  independent of whether they have a YAML binding, rejecting any outcome
  instance whose class was never part of the boot-time scan.
- Split EIP, with items dispatched by simple class name against
  `routes.yaml` bindings — deliberately independent of the originating
  processor's own sealed hierarchy, enabling cross-cutting, reusable outcome
  types (e.g. a shared `ToAuditLog`) that Java's sealed-type rules would
  otherwise forbid.
- Optional Camel `AggregationStrategy` support on `Split`, for collecting
  results via Camel's native splitter engine.
- Multicast delivery is best-effort / fire-and-forget by default: a failed
  send to one destination does not stop delivery to the rest. Failed sends
  are routed to the configured dead letter endpoint if one is set.

## [0.1.0]

### Added
- Initial working prototype: `@GuanacoRoute` processor scanning,
  `TopologyInspector` sealed-hierarchy extraction, `BindingValidator`
  binding validation (STRICT/PERMISSIVE/SILENT modes), `GuanacoRouteBuilder`
  route generation, `GuanacoContext` lifecycle.
- `Drop` outcome for explicit message discard.
- Legacy Camel XML route loading alongside Guanaco-managed routes, for
  incremental migration.
