# Application Documentation Portal

> Status: **proposal / design brief** (2026-06-15). Not yet on the roadmap, not yet
> implemented. This brief records the agreed architecture so a future phase can be sliced from
> it. It describes intended behaviour, not shipped behaviour.

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
| Table definitions | — | **the only real gap** (see below) | manifest listing (spec) + introspection (report) |

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
