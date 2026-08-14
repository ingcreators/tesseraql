# The JVM baseline

Status: **designed 2026-08-14** — four slices below, none shipped yet.

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

One deprecation needs a slice of its own. Camel 4.19 deprecated the MDC logic behind
`setUseMDCLogging` in favour of the `camel-mdc` component, and the runtime uses exactly that
call to carry `traceId` / `spanId` across async boundaries so a lane-dispatched step keeps
logging with the request's ids. The component propagates through the Exchange rather than the
thread's MDC, so adopting it means putting those ids on the exchange and declaring them as
`camel.mdc.customExchangeProperties` — a change to what every log line carries, which does not
belong inside a version bump. The deprecated path still works in 4.22, so slice 2 keeps it
behind a documented suppression and slice 5 migrates it.

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

| Tuning | Scope | Note |
| --- | --- | --- |
| `-XX:+UseCompactObjectHeaders` | Every channel | Opt-in in JDK 25 and 26, default in 27 (JEP 534), so the flag has a known expiry. |
| `--enable-native-access` | Every channel | Silences the JEP 472 warning for the DuckDB JNI driver and prepares for the release that denies it. |
| AOT cache (JEP 514) | jpackage and container only | The cache is tied to the classpath it was trained on. `--modules` and the opt-in codec cache make the fat-jar classpath vary per installation, so the cache cannot be shipped for that channel. |
| `-XX:+AutoCreateSharedArchive` | Fat jar | The CDS equivalent that regenerates itself when the classpath changes — the property the AOT cache lacks. |
| Garbage collector | Unchanged | G1 stays. A short-lived CLI command would prefer serial, but `serve` shares the launcher. |

Nothing in the last three rows is adopted on argument. The slice measures `serve` start-up
time and resident memory on JDK 25 with and without each flag, and only what the numbers
support ships. Measuring is possible only after decision 3, because the Dev Container is on
JDK 21 today.

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

**5 — `camel-mdc`.** Move `traceId` / `spanId` onto the exchange and adopt the component that
replaces the deprecated MDC logic, with the log-line contract in
[deployment.md](deployment.md) restated against what it actually carries afterwards.

## Open decisions

- Which of the four tunings survive measurement (slice 4).
- Whether the fat-jar channel keeps a CDS archive at all, or accepts a slower start in
  exchange for having no cache file to invalidate.
