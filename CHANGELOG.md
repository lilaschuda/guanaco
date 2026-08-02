# Changelog

All notable changes to Guanaco are documented here. Pre-1.0 versions are
`-SNAPSHOT` and not published as tagged releases — v1.0.0 will be the first
tagged release. Version numbers still advance one subversion per completed
set of features, so the commit history stays easy to follow.

## [Unreleased]

## [0.3.0] - In progress

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
