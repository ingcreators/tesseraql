# The JVM baseline

Status: **designed 2026-08-14, complete 2026-08-14** — all four slices shipped: the Spring adapter
left the reactor (`2bb270a0d`, #790), Camel moved to the 4.22 LTS line (`e7bf72bc8`, #796), Java 25
became the baseline (`91bcf5cfa`, #797), and the JVM settings moved into the launchers
(`348300662`, #798). Reconciled 2026-08-20 by reading the tree: the reactor holds no Spring module,
`camel.version` is 4.22.0, and both launchers carry the compact-object-headers and CDS flags.

TesseraQL runs its own process. Every documented way to ship an application — the baked
image, `tesseraql host`, the CLI — starts a JVM the framework packages or picks. That fact
was never written down, so three decisions kept being taken as if the framework were a
library someone embeds: the Java baseline stayed at 21 to protect embedders, a Spring
adapter survived without callers, and no JVM tuning shipped because it would have needed a
per-JDK explanation. This records the position and the three changes that follow from it.

## Who supplies the JVM

| Surface | Who chooses the JVM | Affected by the compile target |
| --- | --- | --- |
| jpackage app images, Homebrew tap, Scoop bucket | The framework (bundled JVM) | No |
| `deploy/Dockerfile`, `deploy/Dockerfile.demo` | The framework (base image) | No |
| Baked application image, `tesseraql host` | The framework | No |
| Fat jar + `bin/tesseraql` launchers | The operator's `PATH` | **Yes** |
| Maven surface (`tesseraql-maven-plugin` under a scaffolded wrapper pom) | The consumer's CI | **Yes** |

Only the last two rows put a JDK requirement on someone else, and neither is an embedding
story. The embedding story — resolving the runtime as a library into an application someone
else starts — has no entry point, no documentation page, and no caller.

## Decision 1 — one runtime, one JVM, so the Spring adapter is retired

`tesseraql-camel-spring-runtime` is two classes: a `@Configuration` that builds a
`TesseraqlRuntime` from a Spring `Environment`, and a `HealthIndicator` that republishes the
operations roll-up through Actuator. No page in `docs/` explains how to use either. The
module is published and version-managed by the BOM, so it reads as a supported surface while
being an artifact of an earlier shape of the project.

The shape it belonged to is already gone. [app-isolation-model.md](app-isolation-model.md)
decision 1 removed in-process mounting and settled that one runtime serves one application
plus the framework's own surfaces; [deployment.md](deployment.md) documents two shipping
patterns, both of which have the framework owning the process.

Camel forces the question now. Camel 4.19 dropped Spring Boot 3 and Spring Framework 6, so
keeping the adapter means either holding Camel back or moving the reactor to Spring Boot 4 —
a dependency-management commitment taken on behalf of nobody.

Retiring it removes the Actuator health bridge. The same roll-up stays reachable over HTTP at
`/_tesseraql/health/live` and `/_tesseraql/health/ready`, which is what the container
`HEALTHCHECK` and every documented probe already use.

## Decision 2 — Camel 4.22 LTS

| | 4.18.3 (current LTS) | 4.22.0 (new LTS) |
| --- | --- | --- |
| Java | 17, 21 | 17, 21, 25 |
| End of life | Feb 2027 | Aug 2027 |

The repository pins 4.18.0. The Java 25 CI job therefore exercises a Camel release that does
not claim to support Java 25 — an inconsistency that predates this document and that decision
3 would otherwise make load-bearing.

Upgrade notes between 4.18 and 4.22 that reach this repository:

- **`camel-test` left `camel-bom` in 4.19.** The version override that pins it away from a
  `-SNAPSHOT` entry can go with it.
- **`DefaultHeaderFilterStrategy` changed defaults in 4.21**: `lowercase` flipped to true, and
  `Camel`-prefixed headers are now filtered in both directions without a component opting in.
  `AssetsRouteBuilder` constructs one, so asset response headers are the thing to verify.
- **`toD` and `enrich` stopped resolving property placeholders** produced at runtime in 4.22.
  Neither EIP is used; the `enrich` occurrences in this codebase are the framework's own
  `enrich:` feature.
- **`camel-platform-http-main` changed its default authentication path** in 4.19. Camel's
  authentication is not used.
- **`csimple` was deprecated** and the `simple` language's `$init{}` blocks now require
  semicolons. Neither is used.

Two changes arrive as improvements rather than work: `CamelObjectInputStream` installs a
JEP 290 deserialization filter by default, and on JDK 25 Camel configures post-quantum hybrid
named groups on `SSLContextParameters` — relevant to the transport-security section of
[deployment.md](deployment.md).

One deprecation needed a slice of its own. Camel 4.19 deprecated the MDC logic behind
`setUseMDCLogging` in favour of the `camel-mdc` component, and the runtime used exactly that
call to carry `traceId` / `spanId` across async boundaries so a lane-dispatched step keeps
logging with the request's ids. The component propagates through the exchange rather than the
thread's MDC, so adopting it meant putting those ids on the exchange — a change to what every
log line carries, which does not belong inside a version bump. Slice 2 kept the deprecated path
behind a documented suppression; slice 5 replaced it (see below).

## Decision 3 — Java 25 is the baseline

`maven.compiler.release` moves to 25, the enforcer range to `[25,)`, and CI to a single JDK.

The cost is one row of the table above: a consumer running the Maven surface needs JDK 25 in
their build environment. That is accepted deliberately. The framework has no released
consumers and is explicitly pre-1.0 about compatibility
([upgrading.md](upgrading.md#compatibility-before-10)), so the baseline is free to move now
and expensive to move later.

The reason is **not** language features. Scoped values would replace one `ThreadLocal` in the
whole repository, structured concurrency and stable values are still preview in 25, and the
rest of the Java 25 surface is syntax this codebase has no use for. The reason is that a
framework which owns its process should have one JVM story: one CI job, one set of flags, one
sentence in the requirements table.

The 1.x compatibility contract (Phase 34) records the result: **the 1.x baseline is Java 25.**

## Decision 4 — the tuning ships in the launchers, not in the documentation

Two of the JDK 25 wins need opt-in flags, which is why they were never adopted: writing "on
25, add this flag" into the getting-started page is a conditional that ages badly. The
conditional does not belong to the reader. Flags belong to the launchers
(`bin/tesseraql`, `bin/tesseraql.cmd`), to `jpackage --java-options`, and to the container
`ENTRYPOINT` — placed **before** `TESSERAQL_JAVA_OPTS` so an operator can still override any
of them.

Nothing here was adopted on argument. Each candidate was measured on JDK 25.0.4 — `serve` on
the example app (median of three boots: time to the liveness endpoint, live heap after a
forced full GC, process RSS) and the short-lived `tesseraql routes` command (median of seven).

| Configuration | `serve` ready | Live heap | RSS | `routes` |
| --- | --- | --- | --- | --- |
| Baseline | 2557 ms | 27,863 KB | 325,356 KB | 577 ms |
| `-XX:+UseCompactObjectHeaders` | 2570 ms | 25,967 KB (−6.8%) | 310,352 KB (−4.6%) | 584 ms |
| CDS archive | 1912 ms (−25%) | 26,883 KB | 260,388 KB (−20%) | 461 ms (−20%) |
| Both | 1900 ms (−26%) | 25,327 KB (−9.1%) | 253,960 KB (−22%) | 458 ms (−21%) |

What ships, and why:

- **A CDS archive.** The largest effect measured, and it is two effects: a quarter off
  time-to-ready and a fifth off resident memory, on both the long-running and the short-lived
  command. Failure was tested rather than assumed: a corrupt archive, a missing one, a read-only
  one and a changed classpath each cost at most a warning line, and the command still succeeded.
- **`-XX:+UseCompactObjectHeaders`.** No effect on start-up, a consistent 7–9% off live heap and
  4–5% off RSS. Opt-in in JDK 25 and 26, default in 27 (JEP 534), so the flag has a known expiry.
- **`--sun-misc-unsafe-memory-access=allow`.** Found by measuring rather than by design: on
  JDK 25 every boot prints three `WARNING` lines because Netty calls `sun.misc.Unsafe`, in
  library code the framework does not own. The flag removes them at no measured cost, and keeps
  Netty working when a future JDK flips that default to deny.

What does not ship:

- **The AOT cache (JEP 514).** The CDS archive delivers the same class of win with none of the
  training-run and exact-classpath discipline the AOT cache demands.
- **`--enable-native-access`.** Nothing was measured for it: the DuckDB JNI driver is a
  module-channel dependency and never loaded in these runs, so no JEP 472 warning appeared.
  It goes in when there is an observation behind it.
- **A different garbage collector.** G1 stays. A short-lived CLI command would prefer serial,
  but `serve` shares the launcher and would pay for it.

### Where the archive comes from, per channel

| Channel | Archive |
| --- | --- |
| Fat jar + launchers | Written on first run into the user cache (`XDG_CACHE_HOME` / `LOCALAPPDATA`); skipped silently when that cannot be written. |
| `deploy/Dockerfile`, `Dockerfile.demo` | Trained at build time against the baked application — a container filesystem is often read-only, and every replica would otherwise pay separately. |
| jpackage app images | Written on first run into `$APPDIR`, which the jpackage launcher substitutes to the installed path. |

Four things this arrangement has to get right, each found by running it rather than by reading
about it:

- **A training run must carry the same flags as the run that reads the archive.** An archive
  records the object-header layout it was built under, and a JVM with a different
  `UseCompactObjectHeaders` setting refuses it — four error lines at every start and none of the
  benefit. The container images train with the flag for that reason, and CI asserts that a
  started image logs nothing under the `cds` tag.
- **`-XX:+AutoCreateSharedArchive` does not rebuild an archive that no longer matches.** A newer
  jar at the same path is not a rebuild trigger: the JVM stops using the archive, prints
  nothing, and start-up returns to where it began — 385 ms back to 525 ms in the measurement,
  invisibly. The launchers therefore name the file after the jar's size, so an upgrade lands on
  a new name and builds a fresh archive, and stale files are removed when a new name appears.
- **jpackage runtimes ship without a base CDS archive**, and without one the JVM silently
  declines to write an application archive at all — which is why the first attempt at this
  channel measured no improvement and produced no file. `jlink --generate-cds-archive` creates
  one, `-Xshare:dump` under the flag converts it to the compact-object-headers layout the
  launcher actually maps, and the layouts that cannot be used are deleted. The cost is ~25 MB
  per platform artifact and a slower first run (941 ms); every run after it is 467 ms against
  623 ms before.
- **`-Xlog:cds=error`** on the launchers. Writing an archive normally reports the classes it
  skipped; in a terminal those read as failures. Errors stay: an archive the JVM refuses still
  says so — which is what an operator who moved an installation would need to see.

### What does not disturb the archive

An archive is validated against the **application** classpath, which is the one jar the
launcher names. Two things that sound like they would invalidate it do not:

- **Opt-in modules.** `--modules` and `tesseraql.modules` load through a child classloader, so
  the application classpath is unchanged. Measured with the 30-jar codec set attached: the
  archive is accepted with nothing logged, and `serve` still starts in 2337–2581 ms against
  2775 ms with sharing off. The modules' own classes are not in the archive and do not become
  faster to load — the saving is the framework's own class loading, which is a fixed amount.
- **Several applications in one host.** `tesseraql host` runs each application in its own
  runtime inside **one** JVM, so all of them share the one archive. Adding applications does
  not invalidate it.

The fixed nature of that saving decides where it shows. On a short CLI command it is a fifth of
the runtime; on a single-application `serve` it is a quarter; on a two-application `host`
(3042 ms against 3030 ms) it disappears into per-application work — compiling routes, applying
migrations, opening pools — which is what start-up is mostly made of once there is more than
one application. Compact object headers still pay there: 32,429 KB of live heap against
36,285 KB.

## Non-goals

- **Spring Boot 4 support.** Decision 1 removes the reason to have an opinion.
- **GraalVM native image.** Unchanged: the runtime's value model is a dynamic classpath
  ([app-developer-distribution.md](app-developer-distribution.md) work item 3).
- **Java 17.** Camel still supports it; nothing here does.

## Slices

**1 — Retire the Spring adapter.** Delete the module; drop its `<module>` entry, the
`spring.version` / `spring.boot.version` properties, the two `dependencyManagement` entries,
the BOM entry, the README module row, and the Dependabot ignore. `ScaffoldedConfigKeys`
registers `TesseraqlRuntimeConfiguration` as a configuration-key consumer, so the keys it was
the only reader of are inventoried in this slice rather than left to fail the drift test.

**2 — Camel 4.22.0.** The version bump, the `camel-test` override removal, and verification
of the asset response headers against the 4.21 filter defaults. Java stays at 21 here so a
failure has one candidate cause.

**3 — Java 25 baseline.** `maven.compiler.release`, the enforcer range, the Dev Container
image, four workflows, both deployment images, and the requirement statements in
`build.md`, `getting-started.md`, `README.md`, `app-developer-distribution.md`,
`development-environment.md` and `release.md`. The `main-protection` ruleset drops
`Maven verify on Java 21` from its required checks in the same change — a required check that
no workflow produces blocks every subsequent pull request.

**4 — Tuning.** Measure first, then ship what the numbers support into the two launchers,
`jpackage.yml`, and the two Dockerfiles.

**5 — `camel-mdc`.** `traceId` and `spanId` are exchange properties now, and `MDCService`
copies them onto whichever thread runs the step — which is what makes them survive a lane
handoff, since the thread changes and the exchange does not. The component also contributes
Camel's own identifiers (`camel.routeId`, `camel.exchangeId`, `camel.messageId`,
`camel.contextId`, `camel.threadId`), so a structured line says which route and exchange it
came from without the framework threading that through by hand;
[deployment.md](deployment.md) documents the full set.

Two seams the component does not cover, both handled explicitly:

- **The processor that creates the ids.** `MDCService` sets the context *before* a processor
  runs, so it cannot know about ids that processor is about to create. `RouteTelemetry` puts
  them on the thread itself as well, covering the rest of its own execution.
- **The access log.** It is written from a completion synchronization, which is not a processor
  and therefore not wrapped; that line sets its own context and clears it.

A test asserts the property that matters rather than the wiring: a route that hands its
exchange to another thread still reports the ids on the far side, and the assertion first
checks that the thread really did change.

## Open decisions

- Which of the four tunings survive measurement (slice 4).
- Whether the fat-jar channel keeps a CDS archive at all, or accepts a slower start in
  exchange for having no cache file to invalidate.
