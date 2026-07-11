# Application Documentation Portal

A TesseraQL application is defined entirely by declarative source: Simple YAML routes/jobs,
2-way SQL, Thymeleaf pages, declarative test suites, and Flyway migrations. Because that source
*is* the specification, documentation derived from it is authoritative and cannot drift from the
implementation — unlike hand-written reference docs.

The documentation portal is a single, browsable **per-application reference**, served in-product
by Studio, that lets an operator or reviewer see, with cross-references between them:

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

## Two layers — spec (deterministic) vs report (run-dependent)

The portal's content splits by nature into two layers:

| Layer | Nature | Artifacts |
| --- | --- | --- |
| **spec** | deterministic, from YAML/SQL/manifest; byte-stable, diffable | `spec.json` (+ spec Markdown), generated at build like `openapi.json` / `htmx-contract.json` and packaged into the `.tqlapp` |
| **report** | run-dependent (test execution, live DB state); not byte-stable | `report.json`, `history.json`, `schema.json` — sidecars in the app home's `.tesseraql/docs/` directory |

Page specs and test specs are **spec** layer. Test results, coverage, and the introspected schema
are **report** layer. The portal renders both but keeps them separate, so the report overlay never
compromises the spec layer's reproducibility guarantee.

## The spec layer — `spec.json`

The `generate` Maven goal emits `tesseraql-generated/docs/spec.json` (plus the spec Markdown), a
deterministic, byte-stable artifact aggregated from the application manifest:

- **Per route/page:** method, path, recipe, inputs with constraints, security (auth, policy,
  CSRF), validation, notifications, response shape, and the bound 2-way SQL statement with its
  declared binds and `/*%if*/` / `/*%for*/` structure.
- **Test cross-references:** the declarative test cases covering each route, linked by SQL file
  path, identity contract, `validate.route`, and `notify.route`/`notify.job`. This is the same
  shared cross-reference index the coverage tooling uses, so the docs and coverage can never
  disagree about which test exercises which route.
- **Migration listing:** every Flyway migration file per datasource — `db/migration`,
  `db/<datasource>/migration`, and `db/migration-<vendor>` vendor overlays — with datasource,
  vendor, version, description, and path. The files are listed, not parsed: there is no DDL
  parsing in the spec layer.

`spec.json` is packaged into the `.tqlapp` archive, and Studio reads it from the packaged app
home. An unpackaged source/dev run has no `spec.json`; the portal then renders a reduced live view
(routes, pages, and SQL only) directly from the manifest it already holds.

## The report overlay — `report.json` and `history.json`

The declarative test suites run through the `test`/`coverage` Maven goals in the
`integration-test` phase and produce `tesseraql-result.json` (pass/fail per case) and
`coverage/sql-coverage.json` (SQL line/branch plus 21 item-coverage kinds). The `report` goal
**ingests** those results — it never re-runs tests — and writes two sidecars into the app home's
`.tesseraql/docs/` directory:

- **`report.json`** — the latest run: summary, thresholds, coverage-gate verdict, per-kind item
  coverage, and per-route test results and SQL coverage. Unlike the compact
  `coverage/sql-coverage.json` (counts only), `report.json` preserves the covered/coverable
  **line sets**, which is what enables per-line SQL highlighting in the portal.
- **`history.json`** — a compact entry per run (`runId`, timestamp, totals, ratios, gate
  verdict), kept as a ring of the **last 20 runs** so the app home never grows unbounded.
  `report.json` always holds only the latest run; `history.json` feeds the trend sparklines.

The artifact is **route-keyed**: the generator joins route → test → result (by name) and
route → SQL once, at generation time, so the portal overlays `report.routes[id]` onto
`spec.routes[id]` with no key-normalization guesswork.

When `report.json` is absent — an app that has never run its tests, or a pure source/dev run —
the portal degrades gracefully to spec-only rendering with "no test run recorded yet" empty
states.

### `report.json` shape

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

`runId` defaults to `generatedAt` (ISO-8601, stamped at generation time). CI can override it with
the `tesseraql.runId` parameter (for example the build number) for stable trend identity across
runs.

## Table definitions — `schema.json`

The spec layer lists migrations; the rich schema view comes from **catalog-wide introspection**
of the migrated database (JDBC `DatabaseMetaData`). The dedicated `schema` goal runs in the
`integration-test` phase after `migrate`, connects with the same
`tesseraql.jdbcUrl`/`username`/`password` parameters, and writes
`appHome/.tesseraql/docs/schema.json`. Schema generation is independent of the test *run* — it
still produces a catalog when tests are skipped — and, like the report, it is overlay-only and
never fails the build.

When the sidecar is absent, the portal shows a "no schema introspected" empty state. Studio
itself never opens a database for the portal: the schema always comes from the sidecar.

### `schema.json` shape

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

## Browsing the portal in Studio

The portal pages live under Studio's `docs` section:

- **Index** — the route/page list with pass/fail and coverage badges, a run summary strip, and
  the migrations table.
- **Per-route spec pages** — the full declared contract (inputs, security, validation,
  notifications, response shape), the covering test cases with their last results, and the bound
  SQL rendered line-by-line, coloured by membership in the covered/coverable line sets.
- **Coverage dashboard** — per-kind coverage ratio bars, the gate verdict, aggregate SQL
  line/branch ratios, uncovered-item and failing-case lists, and pass-rate and SQL line/branch
  trend sparklines over the retained run history.
- **Schema pages** — a catalog index per datasource (tables with column counts) and per-table
  reference pages (parameters `ds` and `name`): columns with primary-key badges, foreign keys
  linked to their target table's page, and unique indexes.

### Search

Search is an in-memory inverted index built at load time over route specs, test specs, rendered
documentation text, and table/column names. For an app-scoped corpus (tens to hundreds of
routes/pages) this is sufficient and adds no dependency. The UX is htmx live-search: typing in
the search input fetches a server-rendered result fragment. The filters `status:failing` and
`coverage:untested` narrow results to failing or untested routes.

### Hand-written docs

Studio renders the application's hand-written `docs/*.md` alongside the generated spec pages.
Markdown is rendered server-side (commonmark-java) to sanitized HTML and placed with `th:utext`.
Rendered bodies must satisfy Studio's strict CSP (`default-src 'self'`, no inline script), so
code highlighting is server-side classes plus Hypermedia Components styles — never inline JS.

## Customizing the presentation

The portal ships as part of the framework-bundled Studio app. App authors customize it through
**blessed seams**, not by editing bundled files:

- the `tql/shell` layout;
- the app's own `templates/nav.html` fragment;
- Hypermedia Components design tokens;
- a small set of documented overridable Thymeleaf fragments.

The customization boundary follows from what is authored versus derived:

- **Customizable (Thymeleaf + hc):** layout, structure, which items appear and how they are
  ordered, navigation, branding, theming.
- **Generated (not customizable):** the derived facts themselves (routes, inputs, security, SQL,
  results, tables). Templates *place* them; they don't author them.

## Release diff

The portal's **Release diff** page consolidates what a promotion changes from the captured
baselines: the API changelog against `.tesseraql/docs/openapi.baseline.json`, the schema DDL
delta against `schema.baseline.json`, and the migration set the app carries. The full
two-tree diff — routes, API, migrations, policies, schema tables — is the
`tesseraql release-diff --app <candidate> --baseline <deployed-tree>` CLI report (Markdown or
`--json`) and the `tesseraql:release-diff` Maven goal, which writes `release-diff.md`/`.json`
beside the other release evidence for the CI governance gate. See
[environment profiles and promotion](promotion.md) for the promotion workflow the diff supports.

## Not currently supported

- **SQL→table dependency graph** (which route reads or writes which table). This needs table
  extraction from 2-way SQL — the hardest semantic piece (dialect variance, dynamic SQL) — and
  is planned, not shipped.
- **A DB-free DDL parser.** The rich schema view comes from introspecting a live, migrated
  database; there is no deterministic spec-layer schema model beyond the migration listing.
- **Multi-datasource introspection** beyond the single datasource the build connects to.
- **Live dev/edit-mode schema introspection.** The schema view is sidecar-only.
- **A scaffold-into-app documentation mode** that emits editable doc routes/templates the author
  owns outright. It would grant maximum freedom but fragments presentation and adds drift and
  maintenance; the blessed seams above are the supported customization path.
- **A documentation-site platform.** The portal deliberately does not become a multi-version /
  i18n / theme-switching / offline documentation platform (per-app branding via the `tql/shell`
  layout, the app's `nav.html`, and hc tokens is in scope; a documentation-site engine is not),
  and no static-site generator or Node toolchain enters the application build. The in-product
  view targets authenticated operators, so there is no anonymous, public, SEO-reachable per-app
  docs surface. If one is ever needed, the Markdown + JSON seam keeps a static export or an
  external SSG a cheap, separable add-on.

## Design notes

- **The seam is Markdown + JSON.** The generator does not depend on any presentation framework.
  It emits deterministic spec Markdown plus structured JSON from the application manifest, and
  Studio consumes them for rendering and search. Keeping the contract at Markdown + JSON means
  the presentation layer is swappable and the reproducible-artifact pipeline never takes on a
  foreign toolchain.
- **The portal is a surface of the bundled Studio app.** Studio is itself a bundled TesseraQL
  application running on the runtime — route YAML plus Thymeleaf pages, mounted through the
  `AppSourceProvider` SPI. The portal's pages are ordinary `query-html` routes fed by `service:`
  bindings (`docs.index`, `docs.route`, `docs.search`, `docs.coverage`, `docs.schema`,
  `docs.table`) instead of SQL, and its templates compose `tql/shell` with Hypermedia Components
  markup exactly like the rest of Studio. That is what makes the portal's presentation
  Thymeleaf-customizable through the seams above.
- **Generation happens at build; Studio reads artifacts.** The heavy aggregation — manifest, test
  specs, the cross-reference index — runs in the build and lands in `spec.json` and the sidecars.
  At runtime Studio only reads those artifacts, renders Thymeleaf, and builds the search index;
  it stays light and opens no database. The only live path is the reduced dev fallback when no
  `spec.json` is packaged.
- **Report and schema sidecars never enter the `.tqlapp`.** `spec.json` is byte-stable and
  packaged into the reproducible archive. Test results and the introspected schema are
  run-dependent and non-deterministic; folding them into the archive would break artifact
  reproducibility. The Maven lifecycle also enforces the placement: `package` seals the `.tqlapp`
  before `integration-test` produces the report, so the run-dependent artifacts live beside (not
  inside) the packaged spec, in `.tesseraql/docs/`. All of these files are generated artifacts —
  never hand-edit them.
