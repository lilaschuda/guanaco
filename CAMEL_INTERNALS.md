# Camel Internals Guanaco Depends On

This document exists because Guanaco is, in places, correct only because of
*undocumented* Apache Camel behavior — not because of anything in Camel's
public DSL contract. That's an ongoing maintenance liability: nothing here
is guaranteed to survive a Camel version bump, including a patch release.

Every entry below names the exact Camel class/method the behavior lives in,
what Guanaco assumes about it, why that assumption matters, and what to
re-check before bumping `camel.version` in the root POM. All entries were
verified by reading Camel's actual source (not documentation, not
inference) against **Apache Camel 4.21.0** — the version currently pinned
in `pom.xml`. Treat "verified against 4.21.0" as the freshness date of
each entry; it does not mean the behavior is guaranteed to hold in any
other version, older or newer.

**Process going forward:** any change to `camel.version` — including a
patch release — should trigger a pass through this entire file before the
bump is merged, not just a routine dependency update. If a behavior below
has changed, either the corresponding Guanaco code needs to change with
it, or (better, where possible) the dependency should be removed in favor
of a more public, more stable mechanism, the way the Saga fix below
replaced a same-route DSL ordering dependency with a `direct:` route
boundary instead of trying to keep the fragile behavior working.

---

## Wire Tap

### 1. `onPrepare` runs on the tap's copy only — this *is* the isolation guarantee
- **Class:** `org.apache.camel.processor.WireTapProcessor`
- **Behavior relied upon:** the `onPrepare` callback passed to `.wireTap(uri).onPrepare(...)` is invoked on the *copy* of the exchange being sent to the tap target, never on the original exchange continuing through the main route.
- **Why it matters:** this is the entire mechanism behind Wire Tap's main-flow isolation guarantee. `GuanacoRouteBuilder.prepareTapCopy` relies on this to safely mutate the tap's body/marker without any risk of affecting the primary flow.
- **If this changes:** Wire Tap's isolation promise breaks silently — the primary flow could start seeing tap-only mutations.

### 2. Bracket notation required for dotted property keys in Simple expressions used as dynamic URIs
- **Class:** Camel's Simple language parser, exercised via `WireTapDefinition.dynamicUri(true)`
- **Behavior relied upon:** `${exchangeProperty[some.dotted.key]}` (bracket notation) is required when the property key itself contains dots — `${exchangeProperty.some.dotted.key}` (dot notation) gets parsed as OGNL-style chained property navigation instead of a literal key lookup, and silently resolves to the wrong (or no) value.
- **Why it matters:** `TAP_TARGET_PROPERTY` (`guanaco.wireTap.targetUri`) contains dots. Getting this wrong doesn't throw — it silently resolves to nothing, and the tap fails quietly.
- **If this changes:** re-verify against a plain dot-notation string first; if Camel's Simple parser ever special-cases this, the bracket workaround becomes unnecessary complexity rather than a requirement.

### 3. `WireTapDefinition`/`ToDynamicDefinition`'s `dynamicUri(true)` re-evaluates its URI per exchange
- **Class:** `org.apache.camel.model.WireTapDefinition`, `org.apache.camel.model.ToDynamicDefinition`
- **Behavior relied upon:** with `dynamicUri(true)` set, the URI expression is evaluated fresh for every exchange, not once at route-build time.
- **Why it matters:** this is what lets Wire Tap's target vary per message, resolved from the outcome the processor returned. **Contrast with Saga below, which has no equivalent toggle** — this distinction was the actual root cause of a real Saga design bug caught before any code was written (see entry 13).

---

## Telemetry dispatch wrapping (plain dispatch, circuit breaker)

### 4. `doCatch` unconditionally marks the exception handled — there is no opt-out
- **Class:** `org.apache.camel.processor.CatchProcessor`
- **Behavior relied upon:** `CatchProcessor.process()` always calls `exchange.setProperty(EXCEPTION_HANDLED, true)` and `exchange.setException(null)` before running its own body — regardless of any configuration. Unlike top-level `onException()`, `doTry()/doCatch()` has **no `.handled(...)` method at all** to opt out of this.
- **Why it matters:** an early version of `attachPlainTo`'s telemetry wrapping used `doCatch(Throwable.class)` to record `onOutcomeFailed`, without rethrowing afterward — this silently swallowed every dispatch failure once telemetry was enabled, since nothing else would ever see the exception again. Fixed by switching to `doFinally` (entry 5) instead of trying to rethrow correctly from inside `doCatch`.
- **If this changes:** if a future Camel version adds a `.handled(...)` equivalent for `doCatch`, `doFinally` remains the better choice regardless (see entry 5) — no need to revisit.

### 5. `doFinally` clears then automatically restores the original exception, unconditionally
- **Class:** `org.apache.camel.processor.FinallyProcessor`
- **Behavior relied upon:** before invoking its own body, `FinallyProcessor` stashes any current exception into the `EXCEPTION_CAUGHT` property and clears `exchange.getException()`. After the body completes, it **unconditionally restores the original exception object** — with no rethrow needed, and no risk of accidentally rewrapping a checked exception (a real bug `doCatch`'s manual-rethrow version had).
- **Why it matters:** this is the actual, current mechanism behind telemetry's failure reporting in both `attachPlainTo` and `GuanacoResilienceHelper.applyCircuitBreaker`. Both read `exchange.getException()` first, falling back to the `EXCEPTION_CAUGHT` property if null (since `getException()` is cleared by the time the `doFinally` body runs).
- **If this changes:** this is the single most load-bearing entry in this file for telemetry correctness — re-verify first on any Camel bump.

### 6. `.onWhen(predicate)` correctly scopes a broad `onException(Throwable.class)` clause
- **Class:** `org.apache.camel.model.OnExceptionDefinition`
- **Behavior relied upon:** Camel selects the most specific matching exception-type clause among multiple registered `onException(...)` handlers, regardless of declaration order, and `.onWhen(...)` further filters within that. A broad `onException(Throwable.class).onWhen(isTapFailure)` clause does not incorrectly intercept a more specific, unrelated exception type (e.g. `MessageRejectedException`) elsewhere in the same route.
- **Why it matters:** the Wire Tap failure handler and the resequence-rejection handler both register `onException` clauses on the same route; this behavior is what keeps them from interfering with each other.

---

## Circuit Breaker / Resilience4j

### 7. `RuntimeExchangeException.wrapRuntimeException`'s "don't double wrap" rule
- **Class:** `org.apache.camel.RuntimeCamelException.wrapRuntimeException` (used internally by the Resilience4j integration)
- **Behavior relied upon:** this helper only wraps a **checked** exception in `RuntimeCamelException`. If the failure is already a `RuntimeException`, it's returned as-is — unwrapped.
- **Why it matters:** `GuanacoResilienceHelper.applyCircuitBreaker` originally called `cause.getCause()` on the caught exception, assuming Resilience4j always wraps failures. For the common case — a plain `RuntimeException` thrown downstream — this returned `null`, silently recording `exceptionType="Unknown"` for nearly every circuit-breaker failure instead of the real exception. Fixed by using `cause` directly.
- **If this changes:** if Resilience4j integration starts wrapping *all* exceptions (checked or not), `cause` (not `cause.getCause()`) is still correct — this fix doesn't need revisiting either way.

---

## Sample

### 8. `SamplingThrottler`'s frequency mode passes the *Nth* message, not the first
- **Class:** `org.apache.camel.processor.SamplingThrottler`
- **Behavior relied upon:** with `messageFrequency=N`, the pass condition is `currentMessageCount % N == 0` — meaning the 2nd, 4th, 6th... message passes for `N=2`, not the 1st/3rd/5th.
- **Why it matters:** test assertions and any documentation describing Sample's behavior need to match this exact semantic, not an assumed "first of every N" model.

### 9. Sample rejection and Drop share the identical underlying mechanism
- **Class:** `org.apache.camel.processor.SamplingThrottler` (rejection path), via `org.apache.camel.processor.StopProcessor`
- **Behavior relied upon:** `SamplingThrottler`'s rejection path is literally `new StopProcessor().process(exchange)` — the exact same `exchange.setRouteStop(true)` signal Guanaco's own `Drop` outcome produces.
- **Why it matters:** this is why Message History's `onCompletion()`-based reporting (entry 12) needed no special-casing for Sample rejection once it already worked for Drop — they're the same signal.

---

## Message History

### 10. `Exchange.CamelMessageHistory` is populated automatically, per node, via advice wrapping
- **Class:** `org.apache.camel.impl.engine.CamelInternalProcessor.MessageHistoryAdvice`
- **Behavior relied upon:** every wrapped node gets a `before()` callback (records the history entry, before invocation) and an `after()` callback (marks it done). `before()` fires *before* the node's own processor runs — so a node that itself causes `routeStop` (Drop, Sample-reject) still gets its own history entry recorded; only the *next* node's advice is skipped.
- **Why it matters:** this is why `reportMessageHistory` needed no special handling for Drop/Sample-rejected exchanges — Camel had already captured their partial history for free, with zero Guanaco instrumentation. The only gap was ever in *reporting* it, not capturing it.

### 11. `onCompletion()` must be registered before any route is defined
- **Class:** `org.apache.camel.builder.RouteBuilder.onCompletion()`
- **Behavior relied upon:** calling `onCompletion()` after `getRouteCollection().getRoutes()` is non-empty throws `IllegalArgumentException("onCompletion must be defined before any routes in the RouteBuilder")`.
- **Why it matters:** `GuanacoRouteBuilder.configure()` registers `onCompletion()` (when telemetry is active) before calling `from(...)` for exactly this reason. This ordering constraint is easy to violate by accident if `configure()` is ever restructured.

### 12. `onCompletion()` fires once per exchange regardless of how routing finished
- **Class:** `org.apache.camel.builder.RouteBuilder.onCompletion()`, backed by Camel's `Synchronization`/unit-of-work mechanism
- **Behavior relied upon:** the registered handler runs exactly once per exchange — on success, on exception, *and* on a `routeStop`-based early exit — because it's a `Synchronization` callback on the exchange's unit of work, not a step in the processor chain. Nothing that skips pipeline steps (like `routeStop`) can skip it.
- **Why it matters:** this is the entire reason Message History uses a single `onCompletion()` handler instead of scattering reporting calls across every existing completion/failure hook. It's also what makes it uniformly cover paths that have *no* existing hook at all (Drop, Sample-rejection, idempotent skip-duplicate) for free.

---

## Saga

### 13. `SagaDefinition` has no dynamic-URI equivalent — compensation/completion are resolved once, at route-build time
- **Class:** `org.apache.camel.reifier.SagaReifier.createProcessor()`
- **Behavior relied upon:** `compensationEndpoint = camelContext.getEndpoint(uri)` (and the same for completion) runs once, during reification — before any message ever flows. Unlike `WireTapDefinition` (entry 3), `SagaDefinition` has no `dynamicUri`-style toggle.
- **Why it matters:** this was caught **during design**, before any Saga code was written, specifically by checking this method before committing to the original proposal (`SagaStep.compensation`/`.completion` as per-message outcome fields). That design would have compiled cleanly and been silently wrong — only the first message's compensation/completion target would ever have mattered, since Camel bakes in one fixed endpoint per saga block regardless of what later messages provide. Fixed by moving `compensation`/`completion` to route-level `GuanacoSagaConfig`, resolved once at boot.
- **This is the single highest-value entry in this whole file** — it's a concrete example of internals-checking preventing a bug from ever being written, not just catching one after the fact.

### 14. The *set* of `.option(...)` keys is fixed at boot; only each key's *value* is dynamic
- **Class:** `org.apache.camel.reifier.SagaReifier` (converts `List<PropertyExpressionDefinition>` → `Map<String, Expression>`, built once)
- **Behavior relied upon:** every `.option(key, expr)` call declared in the DSL becomes one entry in a map built once, at reification. The key set can't vary by message; each key's `Expression` is evaluated per exchange, so its *value* can.
- **Why it matters:** this is why `GuanacoSagaConfig.optionKeys` exists as route-level, boot-time config, and why `SagaStep.options()` is validated against that declared set at runtime rather than being allowed to introduce new keys per message.

### 15. `beginStep()` evaluates all option expressions *before* invoking the wrapped child processor — always
- **Class:** `org.apache.camel.saga.RequiredSagaProcessor` (and, by the shared base class, its propagation-mode siblings)
- **Behavior relied upon:** `coordinator.beginStep(exchange, step)` — which evaluates every `.option(...)` expression and snapshots the results — completes before `super.process(exchange, callback)` (the wrapped child) is ever invoked. This ordering is absolute.
- **Why it matters — and the actual limits of what we could verify:** this ordering guarantee is what makes Saga's option-snapshotting *work at all* conceptually, but it also caused a real, hard-to-diagnose bug: `dispatchOutcome` — which sets the exchange properties `.option(...)` reads — was positioned earlier in the same route's DSL chain, and empirically (confirmed via timestamped debug instrumentation, not just source reading) still ran *after* `beginStep()`'s evaluation. Several DSL-mechanism theories were checked directly against Camel source to explain why (`ProcessorDefinition.addOutput()`, `asType()`, `SagaReifier.createChildProcessor(true)`, whether `RouteDefinition` overrides `addOutput()`) — each was individually disproven, and **the exact mechanism was never conclusively identified**. What resolved it was not explaining the mechanism, but sidestepping it: splitting into two genuine Camel routes connected by a `direct:` hop (entry 16), which doesn't depend on same-route DSL ordering at all.
- **If revisiting this:** if a future contributor wants to actually explain the original single-route ordering bug, this is the starting point — but be aware it resisted a genuinely thorough source-reading effort. Don't assume the two-route workaround is unnecessary without empirical (timestamped, instrumented) proof, not just a plausible-looking source trace — source trace confidence has already been wrong once here.

### 16. `DirectProducer` passes the same `Exchange` instance through, with no copy
- **Class:** `org.apache.camel.component.direct.DirectProducer.process()`
- **Behavior relied upon:** `consumer.getProcessor().process(exchange)` — the identical exchange object, synchronously, typically same thread. No copying happens.
- **Why it matters:** this is the fact that makes the two-route Saga fix (entry 15) reliable rather than just "probably fine" — every exchange property set in the first route is guaranteed present, unmodified, when the second (saga-wrapped) route begins, because it's literally the same object.

### 17. Option values are stored per-step and applied as real HEADERS on the compensation/completion exchange
- **Class:** `org.apache.camel.saga.InMemorySagaCoordinator` (`beginStep`, `doFinalize`/`createExchange`)
- **Behavior relied upon:** `beginStep()` evaluates each declared option's expression and stores the result in an internal map keyed by the `CamelSagaStep` object. Later, `doFinalize()` reads that map and calls `answer.getMessage().setHeader(key, value)` for each entry — genuine message headers, not body content or exchange properties, on the exchange sent to the compensation/completion endpoint.
- **Why it matters:** this confirms a compensation route author can read `exchange.getIn().getHeader("orderId")` directly, with no Guanaco-specific unwrapping needed — matches the original design intent exactly.

### 18. Successful compensation does *not* clear the original triggering exception
- **Class:** `org.apache.camel.saga.SagaProcessor.ifNotException()`
- **Behavior relied upon:** the success path of `ifNotException` never calls `exchange.setException(null)`. A `compensate()` call that itself succeeds does not erase the failure that triggered it — that failure still propagates to the caller afterward.
- **Why it matters:** compensation is cleanup, not error handling, matching how a rolled-back database transaction still reports failure even though the rollback itself succeeded. A test originally written assuming the opposite (that `sendBody(...)` would *not* throw after a successful compensation) was caught and corrected against this behavior.

### 19. No automatic in-memory Saga service fallback exists
- **Class:** `org.apache.camel.reifier.SagaReifier.resolveSagaService()`
- **Behavior relied upon:** the resolution order is: explicit `sagaServiceBean` → `sagaService` string ref (**mandatory** registry lookup, throws if absent) → `camelContext.hasService(CamelSagaService.class)` → mandatory registry type-search (throws if nothing found). There is no automatic "just use an in-memory default" fallback anywhere in this chain.
- **Why it matters:** `GuanacoSagaConfig`'s javadoc originally claimed "`null` uses Camel's own in-memory default" — this was **wrong**, discovered by an actual test failure (`No bean could be found in the registry for: org.apache.camel.saga.CamelSagaService`), not caught during design. Fixed by having `GuanacoContext.wireRoutes()` call `this.addService(new InMemorySagaService())` whenever any route configures Saga without an explicit ref — landing on the `hasService(...)` step in the chain above, and correctly staying a no-op for any route that *does* set its own `sagaServiceRef` (since that ref lookup happens earlier in the chain).

---

## Idempotent Consumer

### 20. `skipDuplicate=true` skips via non-invocation, not `routeStop`
- **Class:** `org.apache.camel.processor.IdempotentConsumer.process()`
- **Behavior relied upon:** on a duplicate, with `skipDuplicate=true` (Guanaco's default), the processor calls `callback.done(true); return true` **without** setting `exchange.setRouteStop(true)`. It simply never invokes its own nested children.
- **Why it matters:** because `wireIdempotent` never closes its own DSL block with `.end()`, Resequence/Aggregate/dispatch all end up as children *nested inside* the idempotent-consumer block (matching the general "leave block open" pattern in entry 27 below) — so "skip duplicate" genuinely means "skip everything downstream," but via non-invocation of nested children, not via the `routeStop` flag Drop/Sample-rejection use (entries 9, 15). This distinction mattered when reasoning about whether Message History's single `onCompletion()` mechanism (entry 12) would also cover this case — it does, but for a structurally different reason than it covers Drop/Sample.

---

## ControlBus

### 21. The scripting-scheme guardrail only matches the top-level component scheme
- **Class:** Guanaco's own `BindingValidator.extractScheme()`, exploited by Camel's `controlbus:` URI shape
- **Behavior relied upon:** `controlbus:language:simple?expression=...` is a genuinely different Camel mode (arbitrary expression execution against the `CamelContext`) hiding behind the same top-level `controlbus:` scheme as the safe `controlbus:route?...` mode. A scheme-only check (matching everything before the first colon) cannot distinguish them.
- **Why it matters:** this is a real, pre-existing gap in Guanaco's own scripting-scheme security guardrail, only *exposed* by adding ControlBus support (nothing bound to `controlbus:` before). Closed with an explicit, `controlbus:`-specific check for the `language:` submode, layered on top of the existing scheme check rather than trying to make the generic check smarter.

### 22. `ControlBusProducer` silently no-ops with neither `action` nor `language` set
- **Class:** `org.apache.camel.component.controlbus.ControlBusProducer.process()`
- **Behavior relied upon:** if neither an `action` parameter nor language mode is configured, the producer just calls `callback.done(true)` and does nothing — no exception, no log, nothing observable.
- **Why it matters:** `controlbus:route?routeId=X` with no `action` param compiles and runs cleanly, doing nothing at runtime. Guanaco's boot-time validation requires `action` to be present specifically to catch this before deployment rather than let it silently no-op in production.

---

## General DSL block-stack behavior (pre-dating this document, still load-bearing)

These were established earlier in the project's history and remain true as
of 4.21.0; listed here for completeness since they're exactly the kind of
thing this document exists to track.

### 23. `.rejectOld()` / `.asyncDelayed()` are no-arg toggles, not boolean setters
Easy to mistake for `.rejectOld(true)`-style setters; they aren't.

### 24. `MemoryIdempotentRepository`'s factory method returns the interface, not the concrete class
Returns `IdempotentRepository`, not `MemoryIdempotentRepository` — affects what you can assign the result to without a cast.

### 25. `.end()` on a nested DSL block returns a widened type
Continuing to chain after `.end()` at the original specificity requires an explicit (unchecked) cast — this is why so much of `GuanacoRouteBuilder`'s wiring code carries `@SuppressWarnings({"unchecked", "rawtypes"})`.

### 26. `WhenDefinition` is not a nested class of `ChoiceDefinition`
`.when(...)` actually returns `ChoiceDefinition` itself, due to its own block-stack tracking design — not a `WhenDefinition` as the name might suggest.

### 27. A DSL block must never have `.end()` called while still empty
Throws "Definition has no children" at route-build time. This is also *why* several `wireX` methods (`wireIdempotent`, `wireSaga`) deliberately never call `.end()` at all — leaving the block open so whatever's chained next becomes its child, which is a feature being relied upon, not an oversight.

### 28. Circuit Breaker configuration uses plain JavaBean setters, not a fluent chain
`Resilience4jConfigurationDefinition` doesn't support fluent chaining the way most other Guanaco-touched DSL classes do, due to Camel's own self-referencing generic type constraints on that specific class.

### 29. `GuanacoContext` needs a Spring `ApplicationContext` set before `start()`/`wireRoutes()`
As of v1.0, `GuanacoContext`'s constructor sets a default empty `StaticApplicationContext` automatically, so this no longer needs manual handling for users who don't need real Spring beans — but the underlying requirement (a `SpringCamelContext` needs *some* `ApplicationContext`) is still there underneath the convenience.
