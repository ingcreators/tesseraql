# Modules at runtime scope

Implementation design for [stack-architecture.md](stack-architecture.md) Decision 28: a module
belongs to the application that declared it, and in a stack that becomes literal — each hosted
runtime sees exactly its own application's modules, for expression functions, file codecs, and
JDBC drivers alike. Decision 28 records the requirement, the measurement, and the rejected union
loader; this document records how it is built: the two structural decisions, the code they touch,
the slices, the guards, the tests, and what is deliberately left out. Written 2026-08-18, before
implementation.

Everything below was measured against main at #853 unless marked otherwise.

**Status 2026-08-18: all four slices shipped.** Slice 1 (the registry becomes a value,
capture-at-parse); slices 2 and 3 together (per-runtime `AppModules`, the host's
TQL-APP-4216/4217 refusals, pool-bound drivers — see the correction note in the slices section
for why they merged as one); slice 4 (MCP per-application contexts). The four open questions
were each closed on their recommendation before implementation. Two things the design did not
predict: the slice 2/3 coupling CI caught (`dev`'s DuckDB smoke failed with "No suitable driver"
in the gap between removing the shim and adding the pool binding), and the shared-rule
bind-contract check (`ValidationRuleSets`) turning out to silently no-op on custom functions —
threaded rather than left as the flagged gap.

## What exists today, measured

**The wiring is three lines, and every line is process-global.** `CliModules.installAppExtensions`
(`tesseraql-cli`, `CliModules.java:68-87`) resolves each listed application's `tesseraql.modules`
into its `work/modules` cache, builds one `URLClassLoader` over *all* the caches plus the
`--modules` directory, and then:

```java
Thread.currentThread().setContextClassLoader(loader);
ExpressionFunctions.install(loader);
ModuleDrivers.register(loader);
```

The `dev` verb inlines the same three lines over every stack member (`TesseraqlCli.java:203-212`),
and `mcp` calls the list overload over every member (`McpCommand.java:104`) — both carry the
comment "interim until decision 28 wires modules per runtime". The single-application commands
(`lint`, `test`, `coverage`, `job`, `routes`, `duckdb`) call the one-app overload, where
process-global is correct by construction: one invocation is one application. The Maven mojos
install from the plugin realm. These are the only `setContextClassLoader` calls in the tree.

**`host` wires nothing.** `HostCommand` never touches `ModulesInstaller`, `ExpressionFunctions`,
or the TCCL — a hosted application's declared modules are silently absent, exactly as Decision 28
says ("not degraded — they are absent").

**Nothing puts module jars on a production machine.** `ModulesInstaller.install` resolves through
the embedded ShrinkWrap Maven resolver into `<work>/modules` and verifies `modules.lock`
(sha256 per artifact). But the `.tqlapp` packager excludes `work/` while including `modules.lock`
(`AppPackager.java:41-55`) — so a package ships the declaration and the lock and no jars — and
**there is no `tesseraql install` command**: `AppInstaller` is a library whose only callers are
`AppUpgrader` (canary staging), and it only extracts the zip. Decision 28's deployment note
("resolution happens at install time") describes a step that currently has no owner.

**The function registry is one `static volatile` map** (`ExpressionFunctions.java:27`), swapped
whole by `install` ("replacing any previous installation") and read in exactly two places, both in
`tesseraql-core`: evaluation (`Expr.java:210`, `evalCustom`) and parse-time arity
(`ExpressionParser.java:191`). Decision 28 counted six callsites; the `dev` widening since added a
seventh (2 reads + 5 installs). Two facts about the read sites shape everything below:

- `ExpressionParser.parse(String)` is static with no configuration object at all — no
  `ParserConfig` exists — and has ~25 direct production callers plus ~20 more through
  `Sql2WayParser`, spread over core, compiler, and yaml.
- The registry's own javadoc rejects a per-thread lookup deliberately: "hot-reload and worker
  threads always see the same function set". Routes hop threads through lane executors
  (`route.threads().executorService(...)`), so a `ThreadLocal`/`ScopedValue` carrier is not a
  rejected style, it is a wrong answer.

**The "tracer and lanes" precedent binds per `CamelContext`, reads per `Exchange`.** One
`DefaultCamelContext` per `TesseraqlRuntime`; the compiler emits a bean *name*
(`TesseraqlProperties.TRACER_BEAN`, `LANES_BEAN`), the runtime binds the instance in its own
context registry (`TesseraqlRuntime.java:393-401`), and processors resolve it per exchange with a
null-safe fallback (`LaneGate.java:33`, `RouteTelemetry.java:148`). The pattern cannot be copied
verbatim: both `ExpressionFunctions` read sites are in `tesseraql-core`, which has no Camel
dependency and no `Exchange` in scope.

**Drivers have no pool seam.** `DataSources` builds every `HikariConfig` from
`jdbcUrl`/`username`/`password` only — no `setDriverClassName`, no `setDataSource` anywhere — so
Hikari falls back to `DriverManager`: JVM-global, first-wins per URL. `ModuleDrivers.register` is
an additive `DriverShim` over that global, keyed by driver class name, no version, no application.

**One per-artifact isolation precedent exists**: signature-verified plugin jars each get their own
named `URLClassLoader` and an explicit-loader `ServiceLoader` call
(`Plugins.isolatedLoader`, `RuntimeExtensions.java:37`) — per-app, held locally, nothing static.

## Structural decision 1: functions bind at parse, and the registry becomes a value

`ExpressionFunctions` becomes an immutable value — `builtInsOnly()`, `load(ClassLoader)`,
`of(Iterable<ExpressionFunction>)` — keeping its name, its validation (a name that is not a Java
identifier, shadows a built-in, or is contributed twice still fails fast, TQL-SQL-2110), and its
whole-set semantics. `arity(name)` and `custom(name)` become instance methods.

**`ExpressionParser.parse(String source, ExpressionFunctions functions)` resolves each custom
call at parse time, and `Expr.Call` captures the resolved `ExpressionFunction`.** Evaluation uses
the captured instance; the parse-time arity check uses the same registry the capture does. This is
the load-bearing choice, for three reasons:

- **Evaluation needs nothing.** `EvaluationContext` (one field, `Map<String, Object> root`) is
  untouched, and its many construction sites — far more numerous than the parse sites — are
  untouched. The alternative, threading the registry through `EvaluationContext`, would touch all
  of them *and* still leave parse-time arity unsolved.
- **The "no longer installed" failure mode dies structurally.** Today `evalCustom` throws
  `IllegalStateException` when the global map was swapped between parse and eval. With capture,
  the function a tree evaluates is the function its registry resolved at parse, always. The
  registry's lifetime is its runtime's lifetime, which contains every tree parsed under it.
- **It preserves the existing javadoc's own rule** — same function set for hot reload and worker
  threads — by making the set a property of the parsed tree, which no thread hop can change.

`parse(String)` remains, delegating to a retained **process default**: the static `install(...)`
keeps today's semantics and today's callers — the single-application commands, the Maven mojos,
and tests (`reset()` stays). This is a default with one override, the `Tracer` shape, not a second
mechanism: in a single-application process the default *is* the application's registry; every
multi-application process (host, dev, mcp) passes its per-application registries explicitly and
never installs the default.

`Sql2WayParser.parse` gains the same registry parameter and threads it down. `SqlResources` — the
process-global cache of parsed framework SQL, shared across runtimes — parses with
`builtInsOnly()` explicitly, turning "bundled framework SQL uses no custom functions" from an
accident into a pinned invariant (a shared cache must not embed one application's functions).

At the runtime layer, the sentence in Decision 28 — "bound where the tracer and lanes already
bind" — is honoured literally: the runtime binds its registry in its own `CamelContext` registry
under a new `TesseraqlProperties.FUNCTIONS_BEAN`, and the one parse site that runs with only an
endpoint in hand (`TesseraqlSqlProducer.doStart`, which parses SQL at producer start and at export
time) resolves it exactly the way `LaneGate` resolves lanes: per-context lookup, null-safe
fallback to the process default. Core itself stays Camel-free; the bean exists for the components
layer, not for `Expr`.

**Rejected: the registry rides `AppManifest`.** Attaching the loaded registry (and its
classloader) to the manifest would spare most signature changes — `RouteCompiler`, `AppLinter`,
`StudioService`, and the MCP tools all hold a manifest already. But a manifest is a value (parsed
files); a classloader is a resource with a lifecycle. Fusing them gives the loader an owner nobody
closes — which is precisely the defect being retired: today's union loaders are never closed. The
registry travels as an explicit parameter beside the manifest, and the loader has one owner.

**Rejected: `ThreadLocal`/`ScopedValue` carrier** — see the measurement above; lane executors are
the counterexample the existing javadoc already names. **Rejected: core reads the Camel
registry** — inverts the core→Camel dependency for two lines. **Rejected: one union classloader**
— rejected in Decision 28 itself; nothing here reopens it.

A note for implementation: `Expr.Call` is a record; capturing the function adds a component, which
changes its equality and `toString`. Tests that compare parsed trees compare captured instances —
identical when parsed under the same registry, which is what trees compared for equality share in
practice. If a surprise surfaces, the capture can live outside the record's equality
(a non-record `Call`), but the simple component is tried first.

## Structural decision 2: the runtime owns its module loader, and resolution stays out of `host`

A new runtime-side class — **`AppModules`, in `tesseraql-camel-runtime`** — is the loader's one
owner:

- Built by `TesseraqlRuntime.start` from `appHome` + config: a `URLClassLoader` over the sorted
  jars of `WorkHome.resolve(appHome, config).resolve("modules")` (parent: the runtime's own
  classloader), plus an optional extra directory (dev's `--modules`). Absent or empty directory →
  no child loader, `builtInsOnly()` composed with nothing — an application without modules costs
  nothing.
- Exposes `functions()` (an `ExpressionFunctions` loaded from the loader), `loader()` (for the
  SPI reads and the driver binding below), and `close()` — called in `TesseraqlRuntime.close()`
  after the pools close, giving the loader the lifecycle it has never had. Decision 29's replace
  gets its prerequisite exactly here: a retiring runtime closes its loader; a starting one builds
  its own.
- Feeds exactly the SPIs modules are documented to provide: `ExpressionFunction`, `FileCodec`
  (the codec discovery at `TesseraqlRuntime.java:772` gains an explicit-loader overload, the shape
  `RuntimeExtensions` already uses for plugins), `BlobStoreProvider` (the `tesseraql-s3` module —
  `BlobStores.create(config, appHome)` is already per-application in its inputs and its own error
  message names the module mechanism; it gains the same explicit-loader overload),
  `RuntimeExtension` (below), and `java.sql.Driver` (decision 3 below). The TCCL-resolved SPIs
  that modules do *not* provide — `SecretResolver`, `AppSourceProvider`, `AttachmentScanner` —
  deliberately stay on the base classpath and the plugin mechanism (open question 1).

`RuntimeExtension` belongs on that list because the shipped documentation says so:
`extending.md`'s "Two ways in" names **"a jar added with `--modules`"** as the classpath route,
and the `tesseraql-oidc`/`-saml`/`-scim` leaf modules — test-scoped in the runtime POM, absent
from the distribution, activated by config — are exactly the jars an operator adds that way.
(Those three now ship *on* the runtime classpath and are activated by configuration alone —
[module-channel.md](module-channel.md) decision 2 — so they are no longer the example; the
per-runtime `RuntimeExtension` route this decision builds is unchanged, and serves an
application's own extension jars.)
`RuntimeExtensions.discover` already merges two sources (the base `ServiceLoader.load` and each
plugin jar's own loader, deduplicated by class name); it gains the application's module loader as
a third, between them. This also heals the current shape's worst accident: under `dev`'s union
TCCL today, **every** runtime discovers **every** member's extension jars — inert only where a
config flag happens to gate the extension — while under `host` a module-delivered extension is
silently absent. Per-runtime discovery makes an extension a member's own declaration in both
modes, which is Decision 28's sentence applied to routes-and-beans extensions rather than
functions.

A boundary these three extensions expose, asked in review: their *wiring* is per-application but
their *effect* is stack-scoped — an OIDC or SAML login mounted on one member mints a browser
session in the framework datasource's store, valid across the stack; SCIM provisions the shared
identity store from one member's scope. That is the pre-stack shape (one runtime was one
application) carried forward, and it remains coherent in a stack: declare the module on the
member that fronts sign-in, and the whole stack benefits. Where these extensions *belong* — the
stack surface runtime at the origin, beside the sign-in screens Decision 24 already moved
there — is Decision 14's slice-4 remainder and the authorization-server campaign, and moving
them raises a question this design deliberately does not answer: how the stack level hands
modules to the surface runtime. Decision 28 only makes wiring follow declaration; it neither
blesses nor blocks the later move.

One SPI hides *inside* a module and needs a one-line companion fix, found in review: `PdfEngine`.
`tesseraql-pdf` provides two providers — `PdfFileCodec` (a `FileCodec`) and `OpenHtmlPdfEngine`
(a `PdfEngine`, selected per deployment by the `tesseraql.pdf.engine` system property) — and the
engine lookup happens from within the codec (`PdfFileCodec.java:99` → `PdfEngines.selected()`)
via `ServiceLoader.load(PdfEngine.class)` with no explicit loader, i.e. the TCCL. Today `dev`'s
union TCCL makes that resolve by accident; delete the TCCL mutation and a module-loaded
`PdfFileCodec` can no longer see the engine sitting in its own jar — PDF export would fail with
"No pdf engine 'openhtml'". The fix is self-reference, not a wider SPI list:
`PdfEngines.selected()` resolves against its own defining loader
(`ServiceLoader.load(PdfEngine.class, PdfEngines.class.getClassLoader())`), which *is* the
application's module loader when the jar arrived as a module and the base classpath when it did
not. Slice 2 carries it; the engine-selection property stays process-global (a packaging call, as
its javadoc says).

**The runtime reads `work/modules`; it never resolves.** Resolution reaches Maven repositories,
and the split follows the network assumption, as Decision 28's deployment note states:

- **`dev` resolves at start**, per member (`ModulesInstaller.install(home, config, false)`, which
  also verifies the lock) — as it does today. What changes is what happens next: the union
  loader, the TCCL mutation, and the process-global install at `TesseraqlCli.java:203-212` are
  **deleted**. Each member's runtime builds its own `AppModules` from the `work/modules` the
  resolve just filled. `--modules <dir>` reaches every runtime as `DevMode` state (a new field,
  the same path `embeddedDb` travels) — it stays what it is: a development override composed onto
  **every** runtime, now documented as reaching each runtime's own loader rather than one union.
- **`host` boots offline from what is already on disk** — and absence becomes loud instead of
  silent. An application that declares `tesseraql.modules` and has no populated `work/modules`
  refuses to start (TQL-APP-4216, below) naming the fix: `tesseraql modules resolve`, run against
  the installed application's directory, the same command a developer already runs. When
  `modules.lock` is present, the jars on disk are verified against its sha256 entries — an
  offline check, no resolver — and disagreement refuses too (TQL-APP-4217). Today's behaviour
  (declared modules, silently absent functions, routes failing at parse with "unknown function"
  or worse, silently *not* failing because a neighbour's union supplied the name under `dev`) is
  the fail-open shape this campaign exists to delete.
- **A future `tesseraql install` runs the same resolution at install time.** Building that
  command is Decision 23 ledger territory and deliberately not here; this design only ensures the
  step it will own already exists as a named, refusal-backed operator action rather than a gap.

**MCP resolves at start, per application, like `dev`** — it is the same development loop. The
union call at `McpCommand.java:104` is deleted; `McpDevTools` holds per-application contexts
(loader + registry) beside the `Map<String, Path>` it already keys every tool by, and the tools
that parse — `lint`, `test`, `draft_preview`, `draft_apply`, `manifest_summary` — pass that
application's registry down. This is where the explicit parameter ripples: `AppLinter`,
`AppTestRunner`, and `StudioService` gain a registry alongside their existing inputs (defaulting
overloads keep the Maven mojos and single-application CLI unchanged), and their internal parse
sites — the lint rules, `ValidationRules`, `DecisionTables`, `DecisionSets`, `NotifyEvents`,
`TransitionExecutor`, `ViewFields` — read it from their owner instead of the static. Mechanical,
and bounded: the ~45 `parse` callers are the ceiling, and most sit behind these few owners.

**Module changes need a restart, stated plainly.** `dev --watch` recompiles routes with the
runtime's existing registry; it does not re-resolve modules or rebuild loaders. Editing
`tesseraql.modules` is a restart, in dev and in production alike (in production it is a Decision
29 replace once that ships). A watch that hot-swaps classloaders is machinery this design
declines to build.

## Structural decision 3: drivers bind at the pool

`DataSources` gains the seam it lacks: when the application's module loader supplies a
`java.sql.Driver` (via `ServiceLoader.load(Driver.class, loader)`, filtered to drivers actually
*defined by* the module loader, not merely visible through it) that `acceptsURL`s the pool's
`jdbcUrl`, the pool binds it directly — `HikariConfig.setDataSource(new
DriverBackedDataSource(driver, url, username, password))`, a small runtime-side `DataSource` over
the driver instance. No match → today's `DriverManager` path, unchanged, so base-classpath
drivers keep working everywhere.

What this buys, per Decision 28: `DriverManager` leaves the load-bearing path for hosted
runtimes, and two applications can carry the same driver at different versions — each pool holds
its own `Driver` object from its own loader, and first-wins-per-URL never arbitrates. The DuckDB
pool path (`DuckDbDatasources.configure`) rides the same seam, so per-application DuckDB versions
follow.

Scope, stated with its boundary:

- **Application pools** (`createAll`, `TenantDataSources`) bind from the application's loader.
- **Stack-scoped pools stay on the base classpath**: the framework pool, the migration pool, and
  the surface runtime's main — the framework datasource is stack infrastructure (Decision 22),
  and its driver ships with the deployment, not with any member.
- **`ModuleDrivers`/`DriverShim` stay for the single-application CLI tools** (`duckdb`, `schema`,
  the `DriverManagerDataSource` users), where one process is one application and the global shim
  is harmless. MCP's `schema_introspect` and `ops_status`, being multi-application, switch to the
  per-application loader through the same `DriverBackedDataSource` helper.

## Slices

Four PRs, each independently green and observable:

| # | Slice | Contents | End state |
| --- | --- | --- | --- |
| 1 | The registry becomes a value | `ExpressionFunctions` instance-ification + retained process default; `parse(String, ExpressionFunctions)`; `Expr.Call` capture; `Sql2WayParser` threading; `SqlResources` pinned to `builtInsOnly()`; delegating overloads on `ValidationRules`/`DecisionTables` | Behaviour identical everywhere; the seam exists; `evalCustom`'s "no longer installed" path deleted |
| 2 | The runtime owns its modules | `AppModules` + close ordering; `FUNCTIONS_BEAN` + `TesseraqlSqlProducer` lookup; `RouteCompiler.functions(...)` threading through the binding processors; codec, blob-store, and runtime-extension discovery take the loader; `PdfEngines` resolves against its own defining loader; `dev` drops the union/TCCL; `DevMode` carries `--modules`; `host` gains TQL-APP-4216/4217 | Each hosted runtime parses and evaluates with its own functions; `host` runs modules for the first time, or refuses loudly |
| 3 | Drivers bind at the pool | `DriverBackedDataSource`; `DataSources`/`TenantDataSources` accept the loader; DuckDB pool path; stack pools explicitly unchanged | Two applications, two driver versions, no `DriverManager` arbitration |
| 4 | MCP per-application context | Per-app loader+registry in `McpDevTools`; the `AppLinter`/`AppTestRunner`/`StudioService` registry parameter; union call + "interim" comments deleted; `schema_introspect`/`ops_status` on the per-app driver path | Every MCP tool answers from the named application's modules, exactly as `dev` serves it |

Slice 1 is pure refactoring with one deliberate behaviour deletion and must land first. Slices 3
and 4 are independent of each other; both need 2 (slice 4 needs 2 only for `AppModules`, which it
reuses from the CLI side — `tesseraql-cli` already depends on the runtime module). If review
prefers, 3 can merge before or after 4.

**Correction, found by CI at implementation (2026-08-18): slices 2 and 3 are one deployable
unit.** Slice 2 deletes `dev`'s `ModuleDrivers.register` — the global shim that made
module-delivered JDBC drivers reachable — while the pool-level binding that replaces it is
slice 3's. Between the two, `dev` with a driver module (the DuckDB smoke) fails with "No
suitable driver". The split stands for review, but they merge together.

## Guards

- **TQL-APP-4216** — an application declares `tesseraql.modules` and its `work/modules` holds no
  jars, raised per application at host start, before any runtime boots (the
  `StackFrameworkGuardTest` placement), naming the application and the fix
  (`tesseraql modules resolve`). `dev` never meets it — it resolves first.
- **TQL-APP-4217** — `work/modules` disagrees with `modules.lock` (sha256, offline): a jar the
  lock does not name, a named artifact absent, or a checksum mismatch. Raised at the same point.
  An absent lock is accepted (it is optional today; making it required is a separate decision).
- **The framework-SQL pin** — `SqlResources` parses with `builtInsOnly()`; a test asserts a
  bundled framework SQL resource referencing a custom function fails to parse. The shared cache
  can then never embed application state.
- **The flipped-by-design assertion, named**: `ExpressionFunctionsTest` currently asserts that
  evaluation fails after `reset()` ("no longer installed"). Capture-at-parse makes the opposite
  true — a tree evaluates with the functions it was parsed under. The test is rewritten to pin
  the new property, with a comment recording the flip.
- **No new guard for cross-application name collisions** — the defect (last-install-wins, or a
  neighbour's same-named function answering) becomes structurally impossible rather than guarded,
  and the headline test below asserts the property positively.

## Test plan

Per slice, naming the existing files they extend:

**Slice 1**
- `ExpressionFunctionsTest`: the flip above; validation unchanged (shadowing, duplicates,
  identifiers, TQL-SQL-2110); `builtInsOnly`/`load`/`of` construction.
- `ExpressionParserTest` (or `ExpressionDepthTest`): explicit-registry parse resolves a custom
  function's arity; the no-registry overload still reads the process default; unknown name still
  errors with the `tesseraql.modules` hint.
- A capture test: parse under registry A, evaluate — then install a different process default and
  evaluate again, same result (the tree is immune to the swap).

**Slice 2**
- The headline IT, in `MultiAppHostIntegrationTest` or `StackModeIntegrationTest`: two
  applications declaring **same-named functions with different semantics** (fixture jars with a
  `ServiceLoader`-registered `ExpressionFunction`, the shape `CliModulesTest` already builds);
  each application's route answers with its own. This is Decision 28's sentence — "an
  application's behaviour is a function of its own declarations, in a stack exactly as alone" —
  as an assertion.
- A guard test in the `StackFrameworkGuardTest` shape: declared-but-unresolved refuses with 4216
  before any runtime starts; lock mismatch refuses with 4217; agreement passes; an application
  with no declaration passes with no loader.
- `TesseraqlSqlProducer` path: a route whose SQL references a module function parses at producer
  start under the runtime's registry (covered by the headline IT; a focused unit test on the
  bean-lookup fallback mirrors `LaneGate`'s).
- Loader lifecycle: after `TesseraqlRuntime.close()`, the loader is closed (assert via
  `URLClassLoader.close` observability or a seam on `AppModules`).
- `dev`: the existing ci dist smoke already boots `dev --stack examples`; no example declares
  modules, so behaviour is unchanged there by construction.
- The PDF self-reference: with `tesseraql-pdf` on a member's module path and the TCCL left
  untouched, a PDF export renders — the engine resolves from the codec's own jar (the runtime
  module already proves codec `ServiceLoader` discovery with test-scoped `tesseraql-pdf`/`-excel`;
  the case extends that arrangement).
- Extension scope: a member with `tesseraql-oidc` on its module path self-installs on its own
  runtime and on no neighbour's (the runtime POM's test-scoped oidc dependency already stands
  where a module would).

**Slice 3**
- `DataSourcesTest`: a fixture jar with a stub `Driver` accepting a fake URL scheme — the pool
  built with that loader connects through the stub, `DriverManager` never consulted (the stub URL
  is registered nowhere global); without the loader, the `DriverManager` path is untouched.
- Two pools, two loaders, two driver "versions" (two stub jars differing observably): each pool
  answers with its own.
- Stack framework pool: explicitly asserted to ignore member loaders.

**Slice 4**
- `McpDevToolsAcceptanceTest`: a member with a module function — `lint` reports no unknown
  function for that member and *does* for a neighbour using the same name undeclared;
  `draft_preview` compiles against the right registry.
- The existing acceptance flow stays green with zero-module apps (the common case).

## What moves in the docs, and when

With the code PRs, not before: `hosting.md` (the host's module expectations, the two refusals,
the operator's `modules resolve` step), `extending.md`/`app-layout.md` wherever `tesseraql.modules`
is taught (the per-runtime visibility sentence and the restart rule), `ai-mcp.md` (per-application
functions in the dev tools), reference regeneration (4216, 4217), `CHANGELOG.md` (Added: modules
under `host`, per-pool drivers; Changed: per-runtime function visibility; the flip is pre-1.0, a
changelog line and no migration steps), and the Decision 28 shipped-note in
`stack-architecture.md`. The `ExpressionFunctions` javadoc's stale mention of `serve` is corrected
in slice 1 in passing.

## Deliberately not in this design

- **The `tesseraql install` command** (and any notify-a-running-host machinery). Decision 23/29
  territory; this design leaves a named operator step (`modules resolve`) and a refusal that
  points at it. How the resolved cache reaches a machine that cannot run that step —
  packaged into the `.tqlapp`, or carried in a fetched repository bag — is
  [module-channel.md](module-channel.md).

*Correction, 2026-08-20.* Structural decision 2's slice table says "codec, blob-store, and
runtime-extension discovery take the loader". Only the codec side shipped that way: blob stores and
runtime extensions kept reading the thread context classloader, so an application that declared
either in `tesseraql.modules` was served by neither — a blob store answered `TQL-YAML-1108` and an
extension was simply never found. Both take the loader now, and a test pins each direction.
- **Module hot-swap under `--watch`.** Restart is the contract, stated in the docs sweep.
- **Widening the module SPI surface** (secret resolvers, app sources, scanners from module
  jars). Plugins already cover per-app extension jars with an allowlist; merging the two
  mechanisms is its own decision.
- **Making `modules.lock` mandatory.** 4217 verifies it when present; requiring it changes the
  authoring loop and deserves its own yes/no.
- **Camel component jars as modules.** `ComponentGuard` governs components per context already;
  module jars contributing Camel components would need governance treatment first.

## Open questions

Each gated on the slice it blocks, with a recommendation:

1. **The module SPI surface** — *gates slice 2.* Which `ServiceLoader` SPIs does the per-runtime
   loader feed? Recommended: exactly the five with an in-tree module provider or an explicitly
   documented module route — `ExpressionFunction`, `FileCodec`, `BlobStoreProvider`
   (`tesseraql-s3`), `RuntimeExtension` (`tesseraql-oidc`/`-saml`/`-scim`, `extending.md`'s
   "a jar added with `--modules`"), `java.sql.Driver`. (`PdfEngine` is provided by a module too
   but resolves *inside* it via the self-reference fix above, so it does not widen this list.)
   Everything else stays base classpath + plugins: `SecretResolver` is stack-scoped by design
   (Decision 26), `AppSourceProvider` is framework-owned, and `AttachmentScanner` has neither an
   in-tree module provider nor a documented module route — `attachments.md`'s "discovered from
   the classpath" gets tightened in the docs sweep rather than silently widened here. Widening
   later is additive; starting wide is the union loader's silent-divergence risk wearing a new
   coat.
2. **Refuse or warn on declared-but-unresolved modules at `host` start** — *gates slice 2.*
   Recommended: refuse (TQL-APP-4216). A warning that scrolls past while routes later fail — or
   silently mis-answer — at parse is the fail-open shape; the refusal names a two-command fix.
3. **One code or two for the module guards** — *gates slice 2.* Recommended: two (4216 absent,
   4217 lock disagreement), the 4211/4212 precedent: different operator mistakes, different
   remedies, different reference rows.
4. **`modules resolve --stack`** — *gates nothing; slice 2 convenience.* Resolving every member
   of a stack in one command is a small loop over `AppDirectory`. Recommended: yes, in the
   slice-2 PR, since `hosting.md` will teach the operator step and "run it N times" is a worse
   sentence.
