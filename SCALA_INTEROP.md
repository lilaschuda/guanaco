# Scala Interop Guanaco-Scala Depends On

This document exists for the same reason `CAMEL_INTERNALS.md` does: `guanaco-scala_2.13`
and `guanaco-scala_3` are, in places, correct — or in one case, *usable at all* — only
because of behavior that isn't part of either language's or either build tool's public,
guaranteed contract. Nothing here is guaranteed to survive a Scala version bump, a
`scala-maven-plugin` version bump, or (in one case) even a patch release.

Every entry names the exact fact verified, how it was verified (by real, empirical
`mvn clean verify` runs against actual Scala 2.13.18 / Scala 3.9.0 toolchains — not
inferred from documentation), why it matters, and what to re-check before bumping
`scala213.version`, `scala3.version`, or `scala-maven-plugin.version` in the root POM.

**Process going forward:** any change to those three properties should trigger a pass
through this entire file before the bump is merged, the same discipline `CAMEL_INTERNALS.md`
already established for `camel.version`.

---

## Route authoring — a hard, load-bearing constraint

### 1. Scala's `sealed trait` does not produce a genuine JVM sealed type — in either Scala version
- **What's relied upon:** `TopologyInspector.extractRouteOutcomes()` (`guanaco-core`) calls
  `Class.getPermittedSubclasses()` unconditionally and throws `GuanacoInspectionException`
  with no fallback if it returns `null`. This is the same real JEP 409 classfile attribute
  Kotlin's `sealed interface` was verified to produce during the v1.2.0 Kotlin work
  (`guanaco-kotlin`'s `SealedInteropSmokeTest`).
- **What was found:** confirmed empirically, via reflection, that a Scala `sealed trait`
  extending `RouteOutcome<T>` does **not** produce this attribute — `Class.isSealed()` returns
  `false` and `getPermittedSubclasses()` returns `null` — in both Scala 2.13.18 and Scala 3.9.0.
  Both Scala compilers keep sealed-hierarchy bookkeeping in their own metadata format (the
  pickle format for 2.13, TASTy for 3) for their own tooling's use (exhaustiveness checking,
  Scaladoc), not as the plain JVM classfile attribute this reflection call depends on.
- **Why it matters:** a Scala-authored route using a native `sealed trait` for its outcome
  hierarchy **cannot boot as a Guanaco route at all**, in either Scala version. This isn't a
  reduced-safety corner case — it's an unconditional `GuanacoInspectionException` at wiring
  time, for what would otherwise be the obviously idiomatic way to declare a route's outcome
  type in Scala.
- **The required workaround, confirmed working in both versions:** declare the outcome
  hierarchy's sealed interface in **Java**, then implement it with ordinary Scala case
  classes:

```java
  // SmokeRouteJava.java
  public sealed interface SmokeRouteJava extends RouteOutcome<String>
          permits SmokeJavaOptionA, SmokeJavaOptionB {}
```

```scala
  // SealedInteropSmokeTest.scala
  final case class SmokeJavaOptionA(value: String) extends SmokeRouteJava {
    override def body(): String = value
  }
```

  Java's own sealed-interface compilation is unaffected by anything about the Scala
  toolchain, so it reliably produces the real attribute `TopologyInspector` needs. **This is
  the required pattern for authoring a Guanaco route's outcome type in either Scala module —
  not a style preference.**
- **What was deliberately *not* done:** changing `TopologyInspector` in `guanaco-core` to
  also read Scala's own sealed metadata (via `scala-reflect` for 2.13, TASTy-reading for 3)
  was considered and rejected — it would mean two separate, version-fragile extraction paths
  and a real dependency on Scala-specific tooling inside a currently language-agnostic core
  module, directly against the "zero vendor deps in core" principle already established for
  `guanaco-telemetry`.
- **If this changes:** `SealedInteropSmokeTest` in both modules carries a *negative*
  regression guard for this specific fact (asserting `isSealed()` is currently `false`,
  `getPermittedSubclasses()` is currently `null`) — not just the positive workaround test.
  If a future Scala release starts emitting the real attribute, that negative test starts
  failing here, which is the intended trigger to revisit whether the Java-interface
  workaround is still necessary at all.

---

## Build mechanics — mixed Java/Scala source compilation

### 2. The root POM's `maven-compiler-plugin` runs its own blind javac pass unless told not to
- **What was found:** both Scala modules need `.java` sources alongside `.scala` sources
  (per entry 1's workaround). Without extra configuration, the root-inherited
  `maven-compiler-plugin` runs its own default `compile`/`testCompile` executions
  independently of `scala-maven-plugin` — a completely separate javac invocation with zero
  visibility into Scala-compiled types. This fails with `cannot find symbol` for any Scala
  case class a Java `permits` clause references, since javac runs blind to whatever
  `scala-maven-plugin`'s own compiler pass would have produced.
- **The fix:** disable `maven-compiler-plugin`'s `default-compile`/`default-testCompile`
  executions for both Scala modules (`<phase>none</phase>`), and set
  `<compileOrder>Mixed</compileOrder>` on `scala-maven-plugin`'s own configuration, so it
  owns compilation of both file types as one coordinated unit instead.
- **Confidence note:** this mechanism's maturity for Scala 2.13/scalac is long-established.
  Its behavior for Scala 3/dotc was **not** assumed to be identical — it was verified by a
  separately passing `mvn clean verify` run, specifically because several other things this
  session (the doc-generation flag vocabulary, the stdlib compilation story) turned out to
  differ between Scala 2 and Scala 3 tooling in ways that weren't obvious up front.

---

## Standard library / runtime compatibility

### 3. Scala 3.8+ ships its own compiled standard library — safe from 3.8.1 onward, not 3.8.0
- **What was found:** up through Scala 3.7.x, Scala 3 reused the Scala 2.13 standard library
  binary directly. Starting with Scala 3.8, Scala 3 ships a standard library compiled by
  Scala 3 itself, published under the *same* `org.scala-lang:scala-library` coordinate
  (deliberately, so ordinary Maven dependency mediation continues to resolve a single version
  rather than two conflicting artifacts) — explicitly confirmed binary-compatible by the
  Scala team.
- **Why it matters:** this is what makes it safe for `guanaco-scala_2.13`- and
  `guanaco-scala_3`-authored routes to coexist inside one running application, per this
  project's coexistence philosophy — without it, mixing the two would risk two incompatible
  `scala-library` definitions on one classpath.
- **The one real caveat:** Scala 3.8.0 itself shipped with a confirmed regression causing JVM
  linkage errors specifically when Scala 3 code executes Scala 2.13 libraries — fixed in
  3.8.1. `scala3.version` must never be pinned to exactly `3.8.0`.
- **If this changes:** re-verify this entire entry before bumping `scala3.version` past the
  3.8.x/3.9.x line — Scala 3.10 is explicitly where the team's own declared stability freeze
  (built around this exact transition) lifts, and the standard library is expected to start
  diverging further from the 2.13-compatible baseline this entry's coexistence guarantee
  depends on.

---

## Known, currently-unresolved tooling gaps

These are not behaviors Guanaco-Scala relies on — they're confirmed-broken features that
were investigated and deliberately not chased further. Documented here so a future revisit
starts from evidence, not from scratch.

### 4. `guanaco-scala_2.13`: Scaladoc cross-linking (`-doc-external-doc`) doesn't work
Verified: correct jar paths resolve via `maven-dependency-plugin`, the flag syntax is
accepted with no compiler error, but generated links never resolve regardless — consistent
with Scala's own documented, unresolved defect **SI-6803** in the external-doc path-matching
logic. `-doc-footer` works and is in use; `-doc-external-doc` was removed rather than left
non-functional. See the comment in `guanaco-scala_2.13/pom.xml`.

### 5. `guanaco-scala_3`: Scaladoc generation doesn't work — three compounding, distinct problems found

Investigated via a private fork of `scala-maven-plugin`
(`net.alchim31.maven:scala-maven-plugin:4.9.11-guanaco.1`,
`~/src/scala-maven-plugin`, branch `fix/scala3-doc-flags`).

**Problem 1 — fixed and confirmed working:** `ScalaDocMojo.getScalaCommand()`
unconditionally emits Scala-2-vocabulary doc flags (`-doc-format:html`,
`-doc-title`) regardless of the target Scala version. Fixed by branching on
`Context.version().major` (the plugin already has this exact machinery, used
elsewhere for `apidocMainClassName()`). Scala 3's equivalent is `-project`,
passed as a colon-joined single token (`-project:VALUE` via `addArgs`) —
**not** `addOption("-project", value)`, which produces two space-separated
tokens matching Scala 2's convention and gets rejected by dotc's arg parser.

**Problem 2 — fixed via POM configuration alone, no further fork changes
needed:** `ArtifactIds4Scala3.apidocMainClassName()` returns
`"dotty.tools.dotc.Main"` — the plain compiler, not a documentation tool at
all. This is why every doc-only flag was ever rejected: we were never running
a doc generator, just the ordinary compiler with extra unrecognized flags,
which explains the `.class`/`.tasty`-only output initially found in
`target/site/scaladocs`. The real tool is `dotty.tools.scaladoc.Main`, in a
separate artifact (`org.scala-lang:scaladoc_3`) the plugin never declares.
Fixed via the mojo's own existing, undocumented-by-us-until-now configuration
surface: `<scaladocClassName>`, `<sourceDir>` (pointed at
`${project.build.outputDirectory}` with `<include>**/*.tasty</include>` —
Scala 3's scaladoc consumes compiled TASTy metadata via TastyInspector, not
raw `.scala` source, a fundamentally different input model than Scala 2's
scaladoc), and the mojo's `<dependencies>` element for the extra
`scaladoc_3` artifact.

**Problem 3 — currently unresolved, stopped here:** with 1 and 2 fixed,
`dotty.tools.scaladoc.Main` genuinely runs and reaches real HTML-rendering
code (`HtmlRenderer`) — but crashes with `NoClassDefFoundError:
com/fasterxml/jackson/annotation/JsonSerializeAs`. Root cause traced to
`HtmlRenderer`'s constructor unconditionally initializing
`StaticSiteContext.staticSiteRoot` (an optional blog/static-site feature we
never asked for), which reaches `tools.jackson.dataformat.yaml.YAMLMapper` —
Jackson **3.x**'s renamed namespace — whose `JacksonAnnotationIntrospector`
needs a Jackson-3-era `jackson-annotations` release containing
`JsonSerializeAs`. Guanaco's own Camel/Spring-driven dependency tree carries
`jackson-annotations:2.22` (confirmed via `dependency:tree`), which appears
to shadow whatever `scaladoc_3` actually needs. Confirmed via source
(`ScalaDocMojo.java:162`) that the mojo unconditionally merges the full
project compile classpath into the scaladoc invocation with **no opt-out
parameter** — meaning Guanaco's own real dependencies can never be kept out
of this process's classpath through configuration alone.

**Tried and found insufficient:** excluding `jackson-annotations` from the
`guanaco` dependency in `guanaco-scala_3`'s own POM — zero effect, identical
crash. The actual conflicting classpath entry was not conclusively
identified before stopping; it's possible the real culprit enters through a
different dependency path than the one excluded, or Maven's mediation
resolved differently than expected.

**Why stopped here rather than continuing:** problems 1 and 2 are genuine,
well-diagnosed, narrowly-scoped upstream bugs — exactly the kind of finding
worth a `scala-maven-plugin` PR regardless of whether Guanaco itself ever
gets full doc generation working. Problem 3 is a different category: a
third-party tool's internal dependency conflict, several layers removed from
anything about Guanaco's own code, requiring either a third round of fork
surgery (filtering the merged classpath) or deeper `dependency:tree`
archaeology to find the real shadowing entry. The effort-to-value ratio
tipped past what's proportionate for this investigation's scope.

**Next steps, if revisited:** (a) get a full, not just `grep`-filtered,
`dependency:tree` output to find every Jackson-family artifact and its exact
path into the classpath, not just `jackson-annotations`; (b) check whether
`scaladoc_3`'s own POM declares a specific `jackson-annotations` version
that dependency mediation should be preferring, and force it explicitly via
`<dependencyManagement>` rather than only excluding the old one; (c) as a
more invasive option, patch the fork to accept a flag suppressing the
`project.getCompileClasspathElements()` merge entirely, since scaladoc's own
resolved dependencies (`scaladoc_3` + transitives) should be sufficient
without Guanaco's own runtime dependencies being present at all.
