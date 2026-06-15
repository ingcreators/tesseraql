# Application Documentation Portal

> Status: **v1, v2 & v3 shipped** (2026-06-15). The design brief below records the agreed
> architecture.
> The *v1 (spec layer)* slice is implemented and merged (PR #60–#67). The *v2 (report overlay)*
> slice is implemented and merged across five sub-slices (report model + `report`/`history.json`
> sidecar, DocService overlay + badges, coverage dashboard + search filters, per-line SQL coverage
> highlighting, and run-trend sparklines); its plan is the
> [Implementation plan — v2 (report overlay)](#implementation-plan--v2-report-overlay) section at
> the end of this document. The *v3 (schema introspection)* slice is implemented and merged across
> two sub-slices (catalog introspection + `schema.json` sidecar, PR #75; schema overlay + table
> reference pages + search, PR #76); its plan is the
> [Implementation plan — v3 (schema introspection)](#implementation-plan--v3-schema-introspection)
> section. The *Table definitions* content area it implements is described above; the route↔table
> dependency graph and a DB-free DDL parser remain deferred to v3.1.

## Motivation

A TesseraQL application is defined entirely by declarative source: Simple YAML routes/jobs,
2-way SQL, Thymeleaf pages, declarative test suites, and Flyway migrations. Because that source
*is* the specification, documentation derived from it is authoritative and cannot drift from the
implementation — unlike hand-written reference docs.

The goal is a single, browsable **per-application documentation portal**, served in-product by
Studio, that lets an operator or reviewer see, with cross-references between them:

1. **Page / route specs** — every HTTP route and HTML page: method, path, recipe, declared
   inputs, security (auth + policy + CSRF), validation, notifications, response shape, and the
   SQL it binds.
2. **Test specs (YAML/SQL)** — the declarative test suites that exercise each route/page, linked
   to the route they cover.
3. **Test results** — pass/fail and coverage for the last run, overlaid onto the same routes.
4. **Table definitions** — the schema the application reads and writes.

Because deny-by-default security and ABAC policies are declared per endpoint, the portal doubles
as a security/audit artifact: for each endpoint it surfaces auth type, required policy, the input
whitelist, and which SQL/statements run.

## Key architectural decision: the seam is Markdown + JSON

The generator does **not** depend on any presentation framework. It emits two tool-agnostic
streams from the `AppManifest`:

- **Deterministic spec Markdown + structured JSON** — byte-stable, diffable, produced in the
  Maven build like the existing `openapi.json` / `htmx-contract.json` artifacts (reproducibility,
  design ch. 48).
- These are then **consumed** by Studio for rendering and search.

Keeping the contract at Markdown + JSON means the presentation layer is swappable and the
reproducible-artifact pipeline never takes on a foreign toolchain.

### Two layers — spec (deterministic) vs report (run-dependent)

The four content areas split by nature, and the codebase already embodies this split:

| Layer | Nature | Produced by (today) |
| --- | --- | --- |
| **spec** | deterministic, from YAML/SQL/manifest; byte-stable, lint-checkable | `GenerateMojo` → `openapi.json`, `htmx-contract.json` |
| **report** | run-dependent (test execution, live DB state); not byte-stable | `AppTestRunner` → `tesseraql-result.json`, `coverage/sql-coverage.json` |

Page specs and test specs are **spec** layer. Test results, coverage, and introspected schema are
**report** layer. The portal renders both but keeps them separate so the report overlay never
compromises the spec layer's reproducibility guarantee.

## Presentation decision: Studio-only (single surface)

The portal is rendered **only** in Studio — server-side, JVM-only, shipped with the runtime,
using Thymeleaf + Hypermedia Components. **No Astro/Starlight/MkDocs or other static-site
generator is adopted**, and **no Node toolchain enters the build.**

Crucially, **Studio is itself a bundled TesseraQL application running on the runtime** — not a
bolted-on servlet. Its source lives at `tesseraql/apps/studio/` (route YAML + Thymeleaf pages),
is mounted via the `AppSourceProvider` SPI (`StudioAppProvider`), and its pages are ordinary
`query-html` routes whose data comes from a `service:` binding (a named Java provider invoked
instead of SQL) rather than from a database. For example
[get.yml](../tesseraql-studio/src/main/resources/tesseraql/apps/studio/web/_tesseraql/studio/ui/get.yml)
binds `sql.service: studio.explorer` and renders
[explorer.html](../tesseraql-studio/src/main/resources/tesseraql/apps/studio/web/_tesseraql/studio/ui/explorer.html),
a Thymeleaf template composing `tql/shell` with hc markup. The documentation portal is therefore
**just another surface of this bundled app**, authored the exact same way (see *Studio rendering
and search* below) — which is what makes its format Thymeleaf-customizable.

Rationale:

- Keeps the build Maven-complete and the spec artifact byte-reproducible (no npm dependency tree,
  no Pagefind/WASM index step).
- Stays SQL-first and hypermedia-native (AGENTS rule 11): one codebase, server-rendered, served
  by the runtime that already serves the app.
- The **report** layer (live test results, coverage, introspected schema) is inherently dynamic
  and authenticated — a static SSG cannot reach it; Studio can.

Accepted trade-offs: the in-product view targets authenticated operators, so there is no
anonymous/SEO public docs reach. If a public, multi-version, SEO documentation site becomes an
adoption need (Horizon 6), it is a **separable** later decision — the Markdown + JSON seam keeps a
static export or an external SSG a cheap add-on. The portal deliberately does **not** try to
become a multi-version / i18n / theming / offline documentation platform; that boundary is where
an SSG would earn its keep, and where Studio must not reinvent one.

## Content mapping — what exists vs what is new

Most raw material already exists as deterministic artifacts; the portal is largely an
**aggregation + presentation** layer plus one genuinely new capability (schema modelling).

| Content | Layer | Status today | Work needed |
| --- | --- | --- | --- |
| Page / route specs | spec | `RouteFile.definition` + `HtmxContractGenerator` | render per-page/route from the manifest |
| Test specs (YAML/SQL) | spec | `TestSuite` model + `TestSuiteLoader` | present declared cases under the route they exercise |
| Test results + coverage | report | `TestReport` → `tesseraql-result.json`; `SqlCoverage` → `coverage/sql-coverage.json` | **ingest** existing artifacts; do not re-run |
| Table definitions | spec + report | migration listing (v1) + catalog introspection → `schema.json` (v3) | **shipped**; SQL→table dependency graph deferred to v3.1 |

References:

- Manifest model — [AppManifest.java](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/manifest/AppManifest.java),
  [RouteFile.java](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/manifest/RouteFile.java),
  [ManifestLoader.java](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/manifest/ManifestLoader.java)
- Existing generators — [OpenApiGenerator.java](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/openapi/OpenApiGenerator.java),
  [HtmxContractGenerator.java](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/openapi/HtmxContractGenerator.java),
  wired by [GenerateMojo.java](../tesseraql-maven-plugin/src/main/java/io/tesseraql/maven/GenerateMojo.java)
- 2-way SQL parse — [Sql2WayParser.java](../tesseraql-core/src/main/java/io/tesseraql/core/sql/Sql2WayParser.java)
- Tests — [TestSuite.java](../tesseraql-test-core/src/main/java/io/tesseraql/test/TestSuite.java),
  `AppTestRunner` in [tesseraql-report](../tesseraql-report/src/main/java/io/tesseraql/report/AppTestRunner.java),
  coverage in [tesseraql-coverage-core](../tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/SqlCoverage.java)
- Studio rendering — [StudioViews.java](../tesseraql-studio/src/main/java/io/tesseraql/studio/StudioViews.java)

## Cross-reference index

The portal's value is the **page → SQL → test → result → table** linkage. That test→route→SQL
linkage is **already computed** by `ManifestCoverage`
([ManifestCoverage.java](../tesseraql-test-core/src/main/java/io/tesseraql/test/ManifestCoverage.java))
— by SQL file path, identity contract, `validate.route`, and `notify.route`/`notify.job`. Today it
is computed on demand inside coverage. The portal should **lift this linkage into a reusable
index** shared by both coverage and docs, rather than recompute it. (Module placement is an open
question — see below.)

## Table definitions

Migrations are raw Flyway SQL (`db/migration/V*__*.sql`, with `db/<datasource>/migration` and
`db/migration-<vendor>` overlays), applied by `AppMigrator` / `AppMigrations`, and are **not**
modelled in `AppManifest`. There is no DDL parser. The only schema model is the scaffolding-only
`TableSchema` / `TableIntrospector`
([TableIntrospector.java](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/scaffold/TableIntrospector.java)),
which reads a live DB via JDBC `DatabaseMetaData`.

Decision:

- **spec layer (cheap, deterministic, DB-free):** add the list of migration files (per datasource,
  with vendor overlays) to `AppManifest` so the portal can always *list* migrations.
- **report layer (rich, run-dependent):** extend `TableIntrospector` from single-table to
  catalog-wide and produce a schema model from the migrated test/CI database. This reuses existing
  code and is reproducible-from-DB-state; the test pipeline already stands a DB up (Testcontainers).

Deferred: a deterministic DDL parser (option B) for a DB-free rich schema view, and the SQL→table
dependency graph (which route touches which table) — the latter needs table extraction from 2-way
SQL and is the hardest semantic piece.

## Studio rendering and search

- **Markdown rendering:** server-side via a standard library (`commonmark-java` or `flexmark`,
  Apache-2.0) → Hypermedia Components markup. This honours "prefer standard libraries" (no
  hand-rolled parser) and AGENTS rule 11 (hc components/behaviors for presentation; any gap is an
  upstream brief, not custom CSS). Studio renders both the generated spec Markdown and the
  hand-written `docs/*.md`. The Markdown dependency lives in `tesseraql-studio` (the renderer),
  **not** in `tesseraql-core` or the generator — the generator emits Markdown *text* and needs no
  Markdown dependency.
- **Search (v1 = option C):** a small **in-memory inverted index** built at mount time over the
  generated spec content and Markdown docs. For an app-scoped corpus (tens–hundreds of
  routes/pages) this is sufficient and adds **no dependency**. UX is htmx live-search (input →
  server fragment of hc result list). Heavier engines (Lucene in a leaf module) or SQL-first RDB
  full-text search (`tsvector`, via a 2-way SQL route) are possible later if the corpus or
  cross-app search outgrows the in-memory index; not needed for v1.

### How the portal is authored (and what is customizable)

Because Studio is a bundled TesseraQL app (see *Presentation decision* above), the portal is built
from the same primitives, not from bespoke Java views:

- **Routes:** `recipe: query-html` pages with `sql.service: docs.spec` (and siblings for the report
  and schema views). A new `docs.*` service provider — registered like the existing `studio.*`
  providers — supplies the aggregated model: the cross-reference index, the spec fields, the
  ingested results/coverage, the schema, and the **pre-rendered** Markdown bodies.
- **Templates:** Thymeleaf composing `tql/shell` + hc, exactly like
  [explorer.html](../tesseraql-studio/src/main/resources/tesseraql/apps/studio/web/_tesseraql/studio/ui/explorer.html).
  `StudioViews`-style model maps (or records) feed `${...}` expressions.

The customization boundary follows from this split:

- **Customizable (Thymeleaf + hc):** layout, structure, which items appear and how they are
  ordered, navigation, branding, theming.
- **Generated (not customizable):** the derived facts themselves (routes, inputs, security, SQL,
  results, tables). Templates *place* them; they don't author them.
- **Markdown bodies:** rendered server-side (`commonmark-java`/`flexmark`) to sanitized HTML and
  placed with `th:utext`. They must satisfy Studio's strict CSP (see explorer.html:
  `default-src 'self'`, no inline script), so code highlighting is server-side classes + hc
  styles, never inline JS (AGENTS rule 11).

**Who customizes — decision (option A):** the portal ships as part of the framework-bundled app,
and app authors customize through **blessed seams**, not by editing bundled files (AGENTS rule 1):
the `tql/shell` layout, the app's own `templates/nav.html` fragment, hc design tokens, and a small
set of documented overridable Thymeleaf fragments. A *scaffold-into-app* mode (option B) — emitting
editable doc routes/templates the author owns outright — is **deferred**; it grants maximum freedom
but fragments presentation and adds drift/maintenance, so it is added only if an app genuinely needs
a bespoke doc layout.

## Proposed slices

1. **v1 — spec layer, DB-free, pure.** Per-route/page reference from the manifest (inputs,
   security, validation, notifications, response, bound SQL statement with declared binds and
   `/*%if*/` structure), plus the declared test cases under each route via the lifted
   cross-reference index. Add migration *listing* to `AppManifest`. Emit deterministic spec
   Markdown + JSON via `GenerateMojo` (byte-stable, ch. 48). Surface it as `query-html` routes in
   the bundled studio app, fed by a new `docs.*` service provider and rendered by `tql/shell` +
   Thymeleaf + hc, with the in-memory search index.
2. **v2 — report overlay.** Ingest `tesseraql-result.json` + coverage JSON; show per-route
   pass/fail and coverage badges. Kept separate from the spec layer.
3. **v3 — schema.** Catalog-wide introspection (extend `TableIntrospector`) → table reference
   pages. Later: SQL→table linkage; optionally a deterministic DDL parser.

## Non-goals

- No static-site generator and no Node toolchain in the build.
- No multi-version / i18n / theme-switching / offline documentation *platform* in Studio
  (per-app branding via the `tql/shell` layout, the app's `nav.html`, and hc tokens is in scope;
  building a documentation-site engine around it is not).
- No *scaffold-into-app* doc mode (option B) in the first cut — bundled portal customized via
  blessed seams (option A) only.
- No DDL parser in v1 (introspection instead).
- No SQL→table dependency graph in v1.

## Open questions

- *Resolved in the v1 plan:* the shared cross-reference index sits in `tesseraql-test-core` as
  `CrossReferenceIndex` (beside `ManifestCoverage`, which delegates to it); the generator splits
  into `RouteSpecGenerator` (`tesseraql-yaml`) and `AppDocGenerator` (`tesseraql-report`).
- *Open:* exact packaging path by which `AppPackager` exposes `spec.json` to the runtime, and
  whether to keep the artifact-read boundary or accept a `tesseraql-studio` → `tesseraql-report`
  dependency for a single always-live path (see the v1 plan's boundary decision).
- Roadmap fit: this is adjacent to the Horizon 6 docs-site / adoption work, but the v1 spec layer
  is cheap and independent of it.

## Implementation plan — v1 (spec layer, DB-free)

Grounded in the current wiring: service providers are registered on
`io.tesseraql.core.service.ServiceProviders` via `.register(name, params -> result)`
([TesseraqlRuntime.java:506,667](../tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/TesseraqlRuntime.java));
the bundled studio app is contributed through the `AppSourceProvider` SPI
([StudioAppProvider.java](../tesseraql-studio/src/main/java/io/tesseraql/studio/StudioAppProvider.java));
derived build artifacts are emitted by
[GenerateMojo.java](../tesseraql-maven-plugin/src/main/java/io/tesseraql/maven/GenerateMojo.java)
mirroring `OpenApiGenerator`. Module facts that shape the design: `tesseraql-report` depends on
`tesseraql-yaml` + `tesseraql-test-core` + `tesseraql-coverage-core`; the maven plugin already
depends on `tesseraql-report`; **`tesseraql-studio` depends only on `tesseraql-core` +
`tesseraql-yaml`** and must stay light.

### Generation happens at build; Studio reads the artifact (the key boundary decision)

The portal's value needs test→route linkage, which lives above `tesseraql-yaml` in
`tesseraql-test-core`/`tesseraql-report`. Pulling those into `tesseraql-studio` would expand its
dependency surface (test runner, coverage, identity) for a view that mostly *reads*. So v1 splits
generation from rendering:

- **Build time (heavy, in `tesseraql-report`):** `AppDocGenerator` aggregates manifest + test
  specs + cross-reference index into a deterministic, byte-stable `spec.json` artifact.
- **Runtime (light, in `tesseraql-studio`):** `DocService` *reads* `spec.json`, renders Thymeleaf,
  and builds the search index — no new heavy dependency.
- **Live fallback:** an unpackaged source/dev run has no `spec.json`; `DocService` then renders a
  reduced live view (routes/pages/SQL only) from a yaml-side `RouteSpecGenerator`, which needs only
  the manifest Studio already holds.

This keeps `tesseraql-studio` light and treats the rich spec as one more deterministic build
artifact (ch. 48). *Alternative considered:* make `DocService` depend on `tesseraql-report` and
regenerate live (one code path, always live including edit mode, but a heavier studio/runtime
dependency). Recommended path is artifact-read; the alternative is the documented fallback if the
packaging/path step proves more trouble than the extra dependency.

### Module & class layout

| Module | New / changed | Responsibility |
| --- | --- | --- |
| `tesseraql-test-core` | **new** `CrossReferenceIndex`; refactor `ManifestCoverage` to delegate | reusable test→route→SQL linkage (today computed on demand inside `ManifestCoverage`) |
| `tesseraql-yaml` | **new** `RouteSpecGenerator`; add `List<MigrationFile> migrations` to `AppManifest` + loader scan + index | manifest → route/page/SQL spec model (+ markdown); migration *listing* (spec-layer table part) |
| `tesseraql-report` | **new** `AppDocGenerator` (wraps `RouteSpecGenerator`, adds test cross-ref) → `DocModel` + `toJson` | the full deterministic `spec.json` (+ markdown bundle) |
| `tesseraql-maven-plugin` | extend `GenerateMojo` | write `tesseraql-generated/docs/spec.json` (+ markdown); ensure `AppPackager` includes it |
| `tesseraql-studio` | **new** `DocService` + `DocViews`; new bundled pages under `tesseraql/apps/studio/web/_tesseraql/studio/ui/docs/...`; add `commonmark-java` dep | read `spec.json` (live fallback via `RouteSpecGenerator`), render, in-memory search |
| `tesseraql-camel-runtime` | register `docs.*` providers in `TesseraqlRuntime` (beside `studio.*`) | wire the bundled doc pages to `DocService` |

### Steps (each a small, reviewable commit — AGENTS rule 7)

1. **`CrossReferenceIndex` in test-core.** Extract the linkage `ManifestCoverage` computes (SQL
   file path, identity contract, `validate.route`, `notify.route`/`notify.job`) into a reusable
   class; make `ManifestCoverage` delegate. No behaviour change — covered by the existing
   `ManifestCoverageTest`. Add a focused unit test for the index itself.
2. **Migration listing in the manifest.** Add `List<MigrationFile> migrations` to `AppManifest`
   (only 4 `new AppManifest(...)` call sites today), scan `db/migration`, `db/<datasource>/migration`,
   and `db/migration-<vendor>` in `ManifestLoader`, and include them in `ManifestIndex` for
   reproducibility. `MigrationFile` carries datasource, vendor (if overlay), version, description,
   path — no DDL parsing.
3. **`RouteSpecGenerator` in yaml.** Manifest → ordered `RouteSpecModel` (per route: method, path,
   recipe, inputs with constraints, security auth/policy/CSRF, validation, notifications, response
   shape, bound SQL with the statement text, declared binds, and `/*%if*/`/`/*%for*/` structure via
   `Sql2WayParser`), plus the migration listing. Deterministic like `OpenApiGenerator` (ordered
   maps, `REPORT` error domain). Unit test asserts a stable model for the `scaffold-demo-app`.
4. **`AppDocGenerator` in report.** Wrap `RouteSpecGenerator`; attach to each route its declared
   test cases (loaded statically via `TestSuiteLoader`, linked via `CrossReferenceIndex`); emit
   `DocModel` + `toJson(manifest)`. Deterministic; unit test against `user-admin-app`.
5. **`GenerateMojo` wiring.** Write `tesseraql-generated/docs/spec.json` (and the markdown bundle);
   confirm `AppPackager` packages it so the runtime can resolve it from the app home. Byte-stable
   assertion test (re-run → identical bytes).
6. **`DocService` + rendering in studio.** Load `spec.json` from the packaged app home (fallback to
   `RouteSpecGenerator` live when absent); add `commonmark-java` to render hand-written `docs/*.md`
   and description prose to sanitized HTML (CSP-safe — no inline JS); produce `DocViews` model maps
   like `StudioViews`.
7. **Bundled pages + providers.** Add `query-html` routes and `tql/shell`+hc Thymeleaf templates
   under `.../ui/docs/` (index, per-route spec, per-table listing, search-results fragment); register
   `docs.index`, `docs.route`, `docs.search` on `ServiceProviders` in `TesseraqlRuntime` beside the
   `studio.*` block.
8. **Search (option C).** Build a small in-memory inverted index in `DocService` over route specs,
   test specs, and rendered doc text at load time; the `docs.search` provider tokenises the query
   and returns ranked hits; the page wires htmx live-search (input → hc result fragment). No new
   dependency.
9. **Verify.** `mvn spotless:apply` then `mvn -B -ntp verify` (and `-pl tesseraql-studio -am test`);
   confirm `BUILD SUCCESS` (not just the piped tail).

### Out of scope for v1 (recap)

Test *results* and coverage overlay (v2), catalog-wide schema introspection and rich table pages
(v3), SQL→table dependency graph, DDL parser, scaffold-into-app customization (option B), and any
SSG/Node tooling.

### Verification & conventions

- Tests accompany each behavioural commit (rule 7); DB-backed paths are not needed in v1.
- Run `spotless` before the final `verify` (codebase convention).
- Land through a PR with CI green (rule 9); never push to `main`.

## Implementation plan — v2 (report overlay)

v1 ships the **spec** layer: a byte-stable `spec.json` (DB-free) that links each route to the
declarative test cases that *cover* it (`TestRef{name, kind, target}`), but carries **no run
results** — no pass/fail, no coverage numbers, no gate verdict. v2 adds the **report** layer: it
overlays the last test run's results and coverage onto those same routes, without compromising the
spec layer's reproducibility guarantee (design ch. 48, *Two layers* above).

The raw material already exists. [`AppTestRunner`](../tesseraql-report/src/main/java/io/tesseraql/report/AppTestRunner.java)
runs the declarative suites against a database (wired by the `test`/`coverage` Maven goals in the
`integration-test` phase) and produces `tesseraql-result.json` (pass/fail per case) and
`coverage/sql-coverage.json` (SQL line/branch + 21 item-coverage kinds). v2 **ingests** this — it
never re-runs tests.

### Design principles (the load-bearing decisions)

1. **The overlay is a separate, optional, sidecar artifact — never inside `.tqlapp`.** `spec.json`
   is byte-stable and packaged into the reproducible `.tqlapp` by `PackageAppMojo`. Test results are
   run-dependent and non-deterministic; folding them into the archive would break artifact
   reproducibility (a flagged-sensitive concern). v2 writes a new `report.json` **sidecar** into the
   app home's `.tesseraql/docs/` directory, alongside (not inside) the packaged spec. The `generate`
   / `package-app` goals and `AppPackager` are unchanged.
2. **Phase ordering forces the sidecar.** The default Maven lifecycle runs `package` (which seals the
   `.tqlapp`) **before** `integration-test` (where `AppTestRunner` runs). The report therefore cannot
   be packaged into the already-sealed archive even if we wanted to — sidecar is the only coherent
   placement, and it is also the correct one.
3. **Graceful degradation.** When `report.json` is absent (an app that has never run its tests, or a
   pure source/dev run), the portal behaves exactly as v1 and renders "no test run recorded yet"
   empty states — mirroring the v1 live-fallback for a missing `spec.json`.
4. **Join once, at generation time, by id/name.** The spec already lists each route's covering test
   *names*; the run produces results by *name*; route coverage is the `kinds.route.covered` set of
   route ids. The generator (which holds the manifest, the `RunResult`, and the
   `CrossReferenceIndex`) performs the route→test→result and route→SQL joins **once**, emitting a
   **route-keyed** `report.json`. The runtime then overlays `report.routes[id]` onto
   `spec.routes[id]` with no key-normalization guesswork.
5. **The existing `sql-coverage.json` is lossy; v2 needs a richer artifact.** `AppTestRunner`'s
   hand-built `writeCoverage` serializes only *counts* (`lineCount()`, `coverableLineCount()`), not
   the `Set<Integer>` line sets in [`SqlCoverageReport`](../tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/SqlCoverageReport.java).
   Line-level highlighting (the rich target) needs those line numbers, so v2 introduces a typed,
   Jackson-serialized `ReportGenerator` that preserves them — superseding the ad-hoc string builder.

### The report artifact — `report.json`

A new typed model in `tesseraql-report`, Jackson-serialized (no hand-built JSON), route-keyed:

```text
ReportDoc(
  schemaVersion: int,
  runId: String,            // overridable via tesseraql.runId (e.g. CI build id); default = generatedAt
  generatedAt: String,      // ISO-8601, stamped by the mojo
  summary: Summary(total, passed, failed, sqlLineRatio, sqlBranchRatio, gatePassed),
  thresholds: Thresholds(sqlLine, sqlBranch, kinds: Map<String,Double>),   // from CoverageThresholds
  gate: Gate(passed, failures: List<String>),                              // from CoverageGate
  kinds: List<KindCoverage(kind, ratio, covered, declared, uncovered: List<String>)>,  // 21 kinds
  routes: Map<String, RouteReport>          // keyed by RouteSpec.id
)
RouteReport(
  covered: boolean,                                   // route id ∈ kinds.route.covered
  tests: List<CaseResult(name, passed, message)>,     // spec cross-ref names ∩ run results, by name
  sql: List<SqlCoverage(file, lineRatio, branchRatio, branchCount, branchOutcomes,
                        coveredLines: List<Integer>, coverableLines: List<Integer>)>,
  itemCoverage: Map<String, Double>                   // per-kind ratio relevant to the route
)
```

Sources: `summary`/`tests` from [`TestReport`/`TestResult`](../tesseraql-test-core/src/main/java/io/tesseraql/test/TestReport.java);
`kinds`/`itemCoverage` from [`ItemCoverage`](../tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/ItemCoverage.java);
`sql` from `SqlCoverageReport` (now keeping its line sets); `thresholds`/`gate` from
[`CoverageThresholds`](../tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/CoverageThresholds.java)
+ `CoverageGate`.

**History.** Each run also appends a compact `HistoryEntry(runId, generatedAt, total, passed,
failed, sqlLineRatio, sqlBranchRatio, gatePassed)` to `.tesseraql/docs/history.json`, kept as a
**ring of the last 20 runs** so the app home never grows unbounded. `report.json` always holds only
the latest run; `history.json` feeds the trend sparklines.

### Module & class layout

| Module | New / changed | Responsibility |
| --- | --- | --- |
| `tesseraql-report` | **new** `ReportGenerator` → `ReportDoc` + `toJson`; reuse `CrossReferenceIndex` for the route→test join | turn a `RunResult` (+ manifest + thresholds/gate + runId) into the typed `report.json`; append the `history.json` ring |
| `tesseraql-maven-plugin` | **new** `ReportMojo` (goal `report`, `integration-test`), or extend `TestMojo`/`CoverageMojo` | run the suites, then write `appHome/.tesseraql/docs/report.json` + `history.json`; **not** added to the `.tqlapp` |
| `tesseraql-studio` | extend `DocService` (`REPORT_PATH`, optional read + overlay) + `DocViews`; new bundled `coverage` page; CSS-only additions in `tesseraql.css` | read `report.json` if present, overlay onto `RouteEntry`, render badges / dashboard / SQL line highlighting / trend |
| `tesseraql-camel-runtime` | register `docs.coverage` provider beside `docs.index`/`docs.route`/`docs.search` in `TesseraqlRuntime` | wire the dashboard page |

Boundary note: `report.json` is read by `DocService` exactly as `spec.json` is today — an
**artifact-read** path. No new heavy dependency enters `tesseraql-studio`; the typed `ReportDoc`
shape is mirrored as a small studio-side record (as `DocSpec` mirrors the build model).

### Slices (each a standalone PR, CI-green, merged in order)

1. **Report model + generator + mojo** (no UI). `ReportDoc` records + `ReportGenerator` (typed,
   deterministic JSON, name-join, preserves SQL line sets) in `tesseraql-report`; `ReportMojo`
   writing `report.json` + `history.json` into `appHome/.tesseraql/docs/`, explicitly excluded from
   the `.tqlapp`. Unit tests for the generator; a Testcontainers IT (extending
   `AppTestRunnerIntegrationTest`, which already stands up Postgres) asserts the artifacts and
   schema. No portal change yet.
2. **DocService overlay + badges.** `DocService` reads `report.json` when present and overlays it onto
   `RouteEntry`; `DocViews` exposes the merged model. Add pass/fail and coverage badges to the index
   and per-route pages, plus an index summary strip. Graceful empty states when the overlay is
   absent. Studio unit tests for the merge + degradation.
3. **Coverage dashboard + search filters.** New `query-html` route `.../ui/docs/coverage` with a
   `docs.coverage` provider and a `tql/shell` + hc template: per-kind ratio bars, gate verdict,
   aggregate SQL line/branch, uncovered and failing-case lists, and a nav entry. Extend the in-memory
   search to filter `status:failing` / `coverage:untested`.
4. **SQL line-level highlighting.** Render each route's SQL source (from the spec's statement text)
   line-by-line, coloured by membership in `coveredLines` / `coverableLines`. CSP-safe (server-side
   classes + hc styles, no inline JS — AGENTS rule 11).
5. **History / trend.** Read `history.json` and render pass-rate and SQL line/branch sparklines on the
   dashboard; document the 20-run retention.

### Resolved open items

- **`runId`:** the mojo stamps `generatedAt` (ISO-8601) and defaults `runId` to it; CI may override
  via the `tesseraql.runId` parameter (e.g. the build number) for stable trend identity.
- **History retention:** `history.json` is a ring of the **last 20 runs**.
- **Provider namespace:** the dashboard stays in the `docs.*` family (`docs.coverage`), consistent
  with v1's `docs.index`/`docs.route`/`docs.search`.

### Verification & conventions

- DB-backed report generation is covered by Testcontainers ITs (the existing
  `AppTestRunnerIntegrationTest` pattern), not H2 in a leaf module.
- Studio overlay/merge and degradation are covered by plain unit tests (no DB).
- `report.json` is a generated artifact — never hand-edited (rule 1).
- Build-file / mojo / reproducibility changes are made deliberately (not auto-accepted).
- Run `spotless:apply` before the final `mvn -B -ntp verify`; confirm `BUILD SUCCESS`.
- Land each slice through a PR with CI green (rule 9); never push to `main`.

## Implementation plan — v3 (schema introspection)

v1 ships the **spec** layer and v2 the **report** layer for routes, tests, results, and coverage.
The remaining content area is **Table definitions** — "the only real gap" (*Content mapping* above).
v1 already added migration *listing* to `AppManifest` (the cheap, deterministic spec-layer part is
shipped — the index page renders a migrations table). v3 adds the **rich, run-dependent schema
view**: introspect the migrated database catalog and surface browsable table reference pages, as one
more authoritative, drift-free surface of the same portal. Because deny-by-default endpoints already
expose which SQL runs, a per-table view of the underlying schema rounds out the security/audit story.

This realises the decision recorded under *Table definitions* (report layer: extend
`TableIntrospector` to catalog scope from the migrated test/CI DB) and the *v3 — schema* slice.

### Scope for this round (decisions)

- **Core only:** catalog-wide introspection + table reference pages. The **SQL→table dependency
  graph** (which route reads/writes which table) is the hardest semantic piece (table extraction
  from 2-way SQL, dialect variance, dynamic SQL) and is **deferred to v3.1**, as is a DB-free DDL
  parser.
- **Sidecar only:** schema comes from a build-time `schema.json`; when absent the portal shows a
  "no schema introspected" empty state (mirroring v2's graceful degradation). **No** live
  dev/edit-mode introspection fallback in this cut.

### Design principles (consistent with v2)

1. **Optional, run-dependent sidecar — never inside `.tqlapp`.** Introspection needs a live,
   migrated database, so the result is non-deterministic across environments. v3 writes a new
   `schema.json` **sidecar** into the app home's `.tesseraql/docs/` directory, alongside
   `report.json` / `history.json`. `AppPackager` already excludes source `.tesseraql/` from the
   archive, so the schema never leaks into the reproducible `.tqlapp` — the `generate` /
   `package-app` goals are unchanged.
2. **Generate from the migrated DB the build already stands up.** The `integration-test` phase
   already applies migrations (`migrate`) and runs against a database (Testcontainers in CI). v3
   introspects that same catalog. A dedicated `schema` goal keeps schema generation independent of
   the test *run* (it still produces a catalog when tests are skipped).
3. **Reuse the single-table introspector.** Generalise the scaffolding-only
   [`TableIntrospector`](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/scaffold/TableIntrospector.java)
   /[`TableSchema`](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/scaffold/TableSchema.java)
   (already JDBC `DatabaseMetaData`-based) to catalog scope rather than writing new metadata code.
4. **Graceful degradation + studio stays light.** `DocService` reads `schema.json` exactly as it
   reads `report.json`; the typed build model is mirrored as a small null-tolerant studio record
   (as `ReportOverlay` mirrors `ReportDoc`). `tesseraql-studio` opens **no** database (sidecar-only).

### The schema artifact — `schema.json`

A new typed model in `tesseraql-report`, Jackson-serialized, keyed by datasource:

```text
SchemaDoc(
  schemaVersion: int,
  generatedAt: String,                       // ISO-8601, stamped by the mojo
  datasources: Map<String, CatalogSchema>    // keyed by datasource name
)
CatalogSchema(tables: List<Table>)           // tables sorted by name (deterministic)
Table(
  name, type,                                // TABLE | VIEW
  schema,                                     // db schema/namespace, nullable
  columns: List<Column>,                      // ordered by ordinal position
  primaryKey: List<String>,                   // ordered key columns
  foreignKeys: List<ForeignKey>,
  uniqueIndexes: List<Index>
)
Column(name, jdbcType, sqlTypeName, nullable, autoincrement, defaultValue, size)
ForeignKey(name, columns: List<String>, refTable, refColumns: List<String>)
Index(name, columns: List<String>, unique)
```

The introspection itself (`CatalogSchema` + a `CatalogIntrospector`) lives in `tesseraql-yaml`'s
`scaffold` package beside `TableIntrospector` (JDK `java.sql` only, no new dependency, keeps the
core/yaml boundary). The serializable `SchemaDoc` wrapper + generator live in `tesseraql-report`.

### Module & class layout

| Module | New / changed | Responsibility |
| --- | --- | --- |
| `tesseraql-yaml` (`scaffold`) | **new** `CatalogIntrospector` + `CatalogSchema` | catalog-wide JDBC introspection (`getTables(null, schema, "%", {TABLE,VIEW})` → per table `getColumns`/`getPrimaryKeys`/`getImportedKeys`/`getIndexInfo`) |
| `tesseraql-report` (`docs`) | **new** `SchemaDoc` + `SchemaGenerator` (`toJson`) | introspect the build DB → typed, deterministic `schema.json` |
| `tesseraql-maven-plugin` | **new** `SchemaMojo` (goal `schema`, `integration-test`) | after `migrate`, write `appHome/.tesseraql/docs/schema.json`; reuse the `tesseraql.jdbcUrl`/`username`/`password` param block; never fail the build (overlay-only, like `ReportMojo`) |
| `tesseraql-studio` | **new** `SchemaOverlay` mirror; extend `DocService` (`SCHEMA_PATH`, optional read + degrade) + `DocViews` (`schema()`, `table()`); new bundled pages | read `schema.json` if present; project catalog index + per-table detail; include table/column names in the in-memory search index |
| `tesseraql-camel-runtime` | register `docs.schema` + `docs.table` providers in `TesseraqlRuntime` (beside `docs.coverage`) | wire the new pages |

Boundary note: `tesseraql-report` already depends on `tesseraql-yaml` and may use JDBC at build time
(rule 2 restricts only `tesseraql-core`). `SchemaMojo` is kept separate from `ReportMojo` because the
schema needs only the migrated DB, not the test run (alternative — fold into `ReportMojo` to share
one connection — rejected for that reason).

### Slices (each a standalone PR, CI-green, merged in order — rule 7)

1. **Schema model + introspector + sidecar (no UI).** `CatalogIntrospector` + `CatalogSchema`
   (yaml); `SchemaDoc` + `SchemaGenerator` + `toJson` (report); `SchemaMojo` writing
   `.tesseraql/docs/schema.json`. Unit test asserts deterministic JSON (re-run → identical bytes,
   sorted output). A Testcontainers IT (Postgres, mirroring `ReportGeneratorIntegrationTest`)
   asserts introspected tables/columns/PK/FK against known migrations. `AppPackager` test confirms
   `schema.json` is **not** packed. No portal change yet.
2. **Schema overlay + table reference pages.** `SchemaOverlay` mirror; `DocService` reads
   `schema.json` (optional, degrades to empty); `DocViews.schema()` (datasources → tables with
   column counts) and `DocViews.table()` (columns, PK badges, FKs linked to their target table page,
   unique indexes). New bundled `query-html` pages `.../ui/docs/schema` and
   `.../ui/docs/schema/table` (params `ds`, `name`) on `tql/shell` + hc; register `docs.schema` /
   `docs.table` in `TesseraqlRuntime`; add a "Schema" nav entry; extend the in-memory search index
   with table + column names. Empty state when the sidecar is absent. Studio unit tests (merge +
   degradation, view projection from fixture JSON) and a `StudioIntegrationTest` rendering the
   catalog index + a table page over HTTP. (Split into 2a pages / 2b search if the PR grows large.)

### Deferred (v3.1+)

- The **SQL→table dependency graph** (route↔table cross-links): parse the directive-stripped 2-way
  SQL skeleton (candidate: JSQLParser, Apache-2.0; reuse the
  [`SqlCoverableLines`](../tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/SqlCoverableLines.java)
  AST-visitor pattern over [`Sql2WayParser`](../tesseraql-core/src/main/java/io/tesseraql/core/sql/Sql2WayParser.java)).
- A DB-free DDL parser (spec-layer schema without a live DB).
- Multi-datasource introspection beyond the single build-connected datasource.
- Live dev/edit-mode introspection fallback.

### Verification & conventions

- Catalog introspection is covered by a Testcontainers IT (the `ReportGeneratorIntegrationTest`
  pattern), not H2 in a leaf module.
- Studio overlay/merge, degradation, and view projection are covered by plain unit tests (no DB).
- `schema.json` is a generated artifact — never hand-edited (rule 1).
- Build-file / mojo / reproducibility changes are made deliberately (not auto-accepted).
- Run `spotless:apply` before the final `mvn -B -ntp verify`; confirm `BUILD SUCCESS`.
- Land each slice through a PR with CI green (rule 9); never push to `main`.
