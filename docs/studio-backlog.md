# Studio improvement backlog

A living list of developer-experience improvements for TesseraQL Studio — what has
shipped and what remains — so the work can be picked up across sessions. Priorities
are indicative; revisit as the editor loop matures. See [roadmap.md](roadmap.md) for
the framework-wide plan and [hypermedia-ui.md](hypermedia-ui.md) for the blessed UI
patterns (mandatory rule 11: UI gaps belong upstream in Hypermedia Components).

## Shipped

Studio editor + docs work (2026-06):

- **Live validation, draft visibility, inline apply errors** (#106) — the editor
  validates as you type via `StudioService.preview()`, flags an unsaved draft with a
  compare/discard affordance, and surfaces `INVALID_DRAFT` inline.
- **Adopt hc 0.1.3 `hc-code`/`hc-sparkline`; retire app CSS** (#107) — the editor,
  source display, coverage gutter, and trend charts moved onto kit components; the
  `.source`/`.cov-line*`/`.spark*` rules were deleted from `tesseraql.css`.
- **Real unified diff in the compare panel** (#108) — an LCS line diff (saved source
  → draft) rendered with `hc-code data-mode="diff"`.
- **Server-tokenized SQL highlighting in the docs route view** (#109) — `SqlHighlighter`
  emits the hc 0.1.4 `hc-code__tok` contract; 2-way SQL directives/binds → `meta`.
- **Highlight the source view, diff panel & YAML/templates** (#110) — `YamlHighlighter`,
  `TemplateHighlighter`, and a `Highlighter` extension dispatcher; the read-only source
  view and the diff panel are highlighted by file type.
- **Rendered preview against sample data (A1, slices 1–2)** — a renderable file renders against
  a sample model rather than only validating against an empty one, and the editor shows the
  actual output two ways: the generated HTML/text/JSON, hc-code highlighted, and (for HTML) a
  sandboxed `iframe` visual preview styled with the hc stylesheet. Two shapes render:
  - a **template file** (`.html`/`.tpl`) against the sample as its template variables;
  - a **web route** (`web/**/<method>.yml`) against the sample as the execution context (`params`,
    `sql.rows`, …): `query-html`/`page` resolves `response.html.model` and renders the route's
    template, `query-json` resolves `response.json.body`, applies its `response.json.fields`
    output-field masking, and pretty-prints it.

  The sample is YAML/JSON typed in the editor, prefilled from a colocated `<name>.sample.yml`
  fixture (blank falls back to it). `StudioService.render`/`sampleModel`, the `studio.render`
  provider, the `POST /_tesseraql/studio/render` JSON endpoint, and the
  `/_tesseraql/studio/ui/render` editor fragment; the source page CSP gains `frame-src 'self'`.
- **Run a route's or job's declarative tests from Studio (A2)** — a **route or job** source page
  gains a **Run tests** action that runs the declarative test cases covering it against the dev
  datasource with inline pass/fail: `sql` queries **and writes** (an `INSERT … RETURNING` runs and
  is rolled back) and `validate` rules (their SQL runs against the sandbox) plus the pure (no DB)
  `notify` and `http-call` evaluations (the latter plans a job's outbound step without a network
  call). Gated and sandboxed: enabled only when Studio is writable and
  `tesseraql.studio.testRunner.enabled` is set; each case runs through a `SandboxDataSource` — an
  auto-rollback transaction (commits suppressed, rolled back on close) with a statement timeout and a
  row cap — so a case can neither run away nor persist a write. Contract cases run too, through a
  sandboxed identity service built over the same datasources, so their identity SELECTs are capped
  and rolled back like every other case. New
  `StudioTestService` reusing the declarative `TestRunner` +
  `CrossReferenceIndex`, the `studio.runTests` provider, the `POST /_tesseraql/studio/runTests` JSON
  endpoint, and the `/_tesseraql/studio/ui/run-tests` editor fragment.
- **Live data in the rendered preview (A1 "real bound params" × A2 sandbox)** — the route render
  panel gains a **Use live data** toggle (when the test runner is enabled): instead of a
  hand-authored `sql.rows` fixture, it runs the route's main `sql` query through the same
  `SandboxDataSource` (bind params resolved from the sample's `params`/`query`) and injects the real
  `rows`/`rowCount`, so editing a route previews the actual page/JSON over live dev data.
  `StudioService.RowSource` (a DB-free callback the runtime fills with `StudioTestService.liveRows`);
  `live` flows through the `studio.render` provider, `POST /_tesseraql/studio/render`, and the
  `/_tesseraql/studio/ui/render` fragment.
- **Scaffold CRUD from a table (B3, slices 1–2)** — a **scaffold** page (linked from the explorer
  chrome when enabled) lists the dev datasource's tables, introspected live with the same
  `CatalogIntrospector` the portal's schema view uses, and previews the CRUD slice the generator would
  produce for a chosen table — reusing the CLI's `TableIntrospector` + `CrudScaffolder` so the files
  are byte-identical to `scaffold crud`. Each generated file is shown highlighted with the apply
  disposition (`new`/`unchanged`/`regenerate`/`conflict`); **Create these files** then writes the
  slice via `ScaffoldWriter` (edit detection + optional `force`), reporting written/unchanged/skipped
  files (new routes serve immediately since roadmap Phase 42's hot-reload completion). Database-free `StudioService.scaffoldPreview`/
  `scaffoldApply`; runtime `StudioScaffoldService` owns the introspection. Gated on writable Studio +
  `tesseraql.studio.scaffold.enabled`. `studio.scaffold.tables`/`studio.scaffold.preview`/
  `studio.scaffold.apply` providers, `GET /scaffold/tables` + `POST /scaffold/preview` + `POST
  /scaffold/apply` endpoints, the `/_tesseraql/studio/ui/scaffold` page.
- **New route from the explorer (B3, slice 3)** — a **New route** form on the explorer (when
  writable) takes a `web/**/<method>.yml` path and a recipe and saves a parseable starter skeleton as
  a draft, then opens it in the source editor to finish, reusing the existing validate → apply flow.
  Database-free `StudioService.newRouteDraft`; the `studio.newRoute` provider and `/ui/new` route.
- **Explorer directory tree + filter (C4)** — the flat route/job tables became one directory tree
  folded from the source paths (nested `<details>` folders, each route/job a leaf linking to its
  source with a method/`job` badge), with a live filter box (case-insensitive over
  id/path/recipe/method/source) that prunes the tree, re-rendered server-side via htmx (`hx-get` +
  `hx-select`, no bespoke JS). Database-free `StudioService.explorer(query)`; the tree (a recursive
  `StudioViews` fold + a self-referencing Thymeleaf fragment); `GET /explorer?q=…` carries the query.
- **Concurrent-edit conflict detection (D5, slice 1)** — saving a draft records the source it is
  based on (a sidecar beside the draft); applying detects a source that changed underneath the draft
  and refuses to overwrite it (no last-apply-wins) unless forced — the editor shows a conflict warning
  and a review-gated overwrite checkbox, and `POST /apply` answers `409` without `force`. Database-free
  `StudioService.draftConflicts` + `applyDraft(path, force)`; `STUDIO-4090 → 409` in the error map.
- **Draft overview (D5, slice 2)** — a **drafts** page (linked from the explorer when writable) lists
  every pending draft under `work/studio/drafts` with a link to its editor, a new/edit kind, and a
  conflict badge. Database-free `StudioService.drafts()`; `studio.drafts` provider; `GET /drafts`.
- **Audit trail (D6, slice 1)** — every source-writing action (apply a draft, apply a scaffold) is
  stamped to an append-only `work/studio/audit/audit.jsonl` with who (`principal.loginId`) / what /
  target / when, recorded at the single write point in `StudioService` so no caller path bypasses it.
  An **audit** page (linked from the explorer when writable) lists it newest-first; applied paths link
  to their editor. `applyDraft(…, actor)` / `scaffoldApply(…, actor)` + `auditEntries(limit)`;
  `studio.audit` provider; `GET /audit`.
- **Per-role edit permission (D6, slice 2)** — `tesseraql.studio.editRoles` allow-list refines the
  writable master switch: when set, only a caller holding one of those roles may mutate; every
  mutating endpoint/UI action answers `403` for everyone else and the explorer/source render the
  read-only view. Per-caller from `principal.roles` via the runtime `StudioAccess` gate (the mutating
  routes bind `roles: principal.roles`); `STUDIO-4031 → 403`.

Upstream Hypermedia Components briefs filed and adopted: `hc-code` (read-only block,
gutter, diff), editable `hc-code`, `hc-sparkline`, and read-only syntax highlighting
(issues #253–256, #261). The editable live-highlight overlay (hc issue #264) **shipped in hc 0.1.5**
and is being adopted (see E below).

## Remaining (prioritized)

### A. Tighten the edit → verify loop (highest value, extends what shipped)

1. **Rendered preview against sample/real data** — ✅ **done** (see Shipped): template files and
   **web routes** (`query-html`/`page` → `response.html.model` + template; `query-json` →
   `response.json.body`) render against a fixture and show HTML/text/JSON output plus a sandboxed
   visual `iframe` for HTML — the "Studio as the center of the edit loop" gate (decision point 4) is
   met. Optional follow-ups, not blockers (pick up opportunistically):
   - **PDF preview** — *done*: a `query-export` `format: pdf` route renders an actual PDF in the
     preview panel — its print template is converted to PDF from the sample's `sql.rows` and shown in
     an `<iframe>` (`data:` URL) with a download link. Reuses the canonical PDF codec; Studio stays
     free of the heavy optional `tesseraql-pdf` stack via a `StudioService.PdfRender` callback the
     runtime fills (degrades to a clear message when the module is absent). Source/render CSP gains
     `data:` in `frame-src`.
   - **Output-field masking** — *done*: the JSON preview applies a `query-json` route's
     `response.json.fields` masking (hide/redact per policy/classification/mask), reusing the canonical
     `FieldPolicyApplier` evaluated for the sample principal the developer puts under `principal` in the
     render sample. Studio stays free of the security/compiler stack — the runtime supplies the mask via
     a `StudioService.FieldMask` callback (the A1 live-rows `RowSource` pattern).

   With output-field masking and PDF preview both shipped, A1 is fully complete (template files, web
   routes, live data, JSON field-masking, and PDF export preview).

   The "render against **real** bound params" end — executing the route's SQL through the A2
   sandbox to populate live rows instead of a hand-authored `sql.rows` fixture — is **done** (the
   render panel's **Use live data** toggle; see Shipped). (Email/notification `.html` templates
   already preview via the template-file path: supply `payload`/`event` as the sample.)
2. **Run a route's or job's declarative suite from Studio** — *done* (see Shipped): a **Run tests**
   action runs every declarative case kind covering a route or job — `sql` (read **and write**),
   `validate`, `contract` (through a sandboxed identity service), `notify`, and `http-call` — against
   the dev datasource, sandboxed (auto-rollback) and opt-in.
   - **Live rows into the rendered preview** — *done* (see Shipped): the route render panel's **Use
     live data** toggle runs the route's main `sql` through the sandbox for real rows.
     - **Multi-binding live render** — *done* (net-new, category 3): **Use live data** now also runs
       every named `query` (not only the main `sql`) through the sandbox, injecting each under its
       model name, in authored order against an accreting context — so a route whose template/body
       references `<query>.rows` previews over real data. `StudioService.RowSource` returns the
       results keyed by model name; `StudioTestService.liveRows` runs the main query + each named
       query. Command `steps` (writes) remain a later extension. (Run tests already covered every
       binding via `CrossReferenceIndex.bindings`.)

   Ties to milestone M7 ("schema → verified CRUD in ten minutes").

### B. Creation / scaffolding in the UI

3. **New route / file creation and CRUD scaffold from the explorer** — *done* (slices 1–3): Studio
   was single-file *edit* only; it now also **creates**. "Scaffold CRUD from table" (preview + apply)
   and "new route" both ship, wired to the v3 schema introspection. The hot-reload limit noted here
   was lifted by roadmap Phase 42: the reloader now mounts newly created routes (and un-mounts
   removed ones), so applying serves immediately.
   - **Scaffold CRUD — preview** — *done* (slice 1): a **scaffold** page (linked from the explorer
     chrome when enabled) lists the dev datasource's tables introspected live (`CatalogIntrospector`)
     and previews the CRUD slice the generator would produce for a chosen table, reusing the CLI's
     `TableIntrospector` + `CrudScaffolder` so the output is byte-identical. Each generated file is
     shown highlighted with the apply disposition (`new`/`unchanged`/`regenerate`/`conflict`); the
     slice writes nothing. Database-free `StudioService.scaffoldPreview` (generation + conflict
     status); runtime `StudioScaffoldService` owns the introspection. Gated on writable Studio +
     `tesseraql.studio.scaffold.enabled`. `studio.scaffold.tables`/`studio.scaffold.preview`
     providers, `GET /scaffold/tables` + `POST /scaffold/preview` endpoints, `/ui/scaffold` page.
   - **Scaffold CRUD — apply** — *done* (slice 2): **Create these files** writes the previewed slice
     into the app home via `ScaffoldWriter`, honoring the checksum/edit-detection contract — a
     pristine generated file is regenerated, a file the user edited or owns is skipped and reported
     unless `force` overrides it. The result lists written/unchanged/skipped files (written ones link
     to the source editor) and flags newly written routes the manifest did not declare. (When this slice
     shipped the hot reloader only swapped existing routes, so those needed a restart; roadmap
     Phase 42 lifted that — scaffold apply now reloads and the new routes serve immediately.) Database-free
     `StudioService.scaffoldApply`; `studio.scaffold.apply` provider; `POST /scaffold/apply` endpoint.
   - **New blank route** — *done* (slice 3): a **New route** form on the explorer (when writable)
     takes a `web/**/<method>.yml` path and a recipe (`query-json`/`query-html`/`command-json`) and
     saves a parseable starter skeleton as a draft, then opens it in the source editor to finish — so
     creation reuses the existing validate → apply flow. Database-free `StudioService.newRouteDraft`;
     the `studio.newRoute` provider and the `/_tesseraql/studio/ui/new` route.

### C. Explorer / navigation polish

4. **Explorer tree + filter** — *done*: the flat route/job tables became a single directory tree
   folded from the source paths (folders as nested disclosures, each route/job a leaf linking to its
   source), with a live **filter** box (case-insensitive over id/path/recipe/method/source) that
   prunes the tree, re-rendered server-side via htmx (`hx-get` + `hx-select`, no bespoke JS).
   Database-free `StudioService.explorer(query)`; the tree is built in `StudioViews`; the
   `studio.explorer` provider and `GET /explorer?q=…` endpoint take the query.

### D. Editing safety / operations

5. **Draft robustness** — discard exists (#106).
   - **Concurrent-edit conflict detection** — *done*: a draft records the source it is based on, and
     apply refuses to overwrite a source that changed underneath it (no last-apply-wins) — the editor
     shows a conflict warning + a review-gated overwrite confirmation, and the apply endpoint answers
     `409` unless `force` is set. `StudioService.draftConflicts` / `applyDraft(path, force)`.
   - **Draft overview** — *done*: a **drafts** page (linked from the explorer when writable) lists
     every pending draft under `work/studio/drafts` with a link to its editor, a new/edit kind, and a
     conflict badge, plus a count of conflicting drafts. Database-free `StudioService.drafts()`; the
     `studio.drafts` provider and `GET /drafts` endpoint.
   - **Confirm-diff-before-apply** — *done*: the optional `tesseraql.studio.confirmApply` flag makes
     the editor acknowledge the diff before **every** apply (not only on a conflict). When on, the
     source page shows a `required` confirm checkbox by the compare panel, and the UI apply route
     rejects an unacknowledged apply (`STUDIO-4223 → 422`); the conflict force checkbox counts as the
     acknowledgment. UI-only — the programmatic JSON and MCP apply paths are not gated (no human diff
     to review). Runtime `StudioAccess.requireConfirm` / `confirmApply()`.
6. **Granular read-only + audit** — *done* (production hardening): per-role edit permission plus an
   audit trail.
   - **Audit trail** — *done*: every source-writing action (apply a draft, apply a scaffold) is
     stamped to an append-only `work/studio/audit/audit.jsonl` log with who/what/target/when, recorded
     at the single point each write happens so no caller path bypasses it. An **audit** page (linked
     from the explorer when writable) lists it newest-first. `StudioService.applyDraft(path, force,
     actor)` / `scaffoldApply(table, force, actor)` + `auditEntries(limit)`; `GET /audit`.
   - **Per-role edit permission** — *done*: an optional `tesseraql.studio.editRoles` allow-list
     refines the all-or-nothing `readOnly` — when set (and writable), only a caller holding one of
     those roles may mutate; every mutating endpoint/UI action answers `403` for everyone else and the
     explorer/source pages render the read-only view. The decision is per-caller from `principal.roles`
     (runtime `StudioAccess` gate); `StudioService` keeps enforcing the master switch. `STUDIO-4031 →
     403`.

### E. Editor live highlighting — **done (hc #264 shipped in 0.1.5)**

7. Live highlighting of the editable textarea (hc #264) — *done*.
   - **Built-in grammars** — *done* (slice 1): the editable `hc-code` source and sample fields opt
     into `installCodeEditor`'s `data-lang` overlay, grammar chosen by file type
     (`sql`/`yaml`/`html`/`json`); hc bumped 0.1.4 → 0.1.5. `StudioViews.editorLang`; `data-lang` on
     the editor divs.
   - **2-way SQL tokenizer + richer tokens** — *done* (slice 2): the server YAML/template highlighters
     adopt hc 0.1.5's `property` (YAML keys) / `tag` (elements) / `attribute` (plain attrs) tokens —
     directives stay `meta` — so the read-only/diff views match the live overlay's built-in grammars;
     and a `tql-sql` grammar registered via `registerCodeLanguage` in `tesseraql.js` (mirroring the
     server `SqlHighlighter`) gives the editable SQL field live highlighting with 2-way directives as
     `meta`. Editable `.sql` uses `data-lang="tql-sql"`. **E is now fully done.**

### F. Docs portal

8. **Export / share** — OpenAPI/JSON export, printable docs (reuse the PDF codec),
   per-route shareable links (docs are in-app/bearer-only today).
   - **API-spec export** — *done* (slice 1): an **Export** page (linked from the docs chrome) serves
     the app's OpenAPI 3 document and its htmx interaction contract as downloadable JSON, generated
     live from the manifest by the canonical `OpenApiGenerator` / `HtmxContractGenerator` (byte-identical
     to the build's `openapi.json` / `htmx-contract.json`). The download endpoints stream the spec as a
     `Content-Disposition` attachment via the standard `response.file` recipe, bearer-gated like the rest
     of the portal. `DocService.openApiJson`/`htmxContractJson`; `DocViews.export`; `docs.export`/
     `docs.openapi`/`docs.htmx` providers; `/ui/docs/export` (+ `/openapi`, `/htmx`) routes.
   - **Printable docs (PDF)** — *done* (slice 2): the Export page renders the app's route catalog
     (id, method, path, recipe, covering tests) to a PDF table through the canonical PDF codec (the
     `FileCodecs.discover()` path the export routes use, via its built-in grid — no template), shown
     in a preview frame with a `routes.pdf` download link. Studio stays free of the optional
     `tesseraql-pdf` stack: the runtime renders the PDF, degrading to a clear note when the module is
     absent. `DocService.routeCatalog`; `DocViews.routesPdf`; `docs.routesPdf` provider; the
     `/ui/docs/export/pdf` route. (Rich per-page PDF — arbitrary doc-page HTML→PDF — would need
     exposing the raw PDF engine past the row-oriented codec; a later extension.)
   - **Per-route shareable links** — *done* (slice 3, completing F8): docs are bearer-only by
     default; when the operator sets a signing secret (`tesseraql.docs.share.secret`, optional
     `tesseraql.docs.share.ttl`, default 7d), a route page offers a **Share** card with a signed,
     expiring link that opens that one route's **read-only contract** (method/path/recipe, inputs,
     security summary, validations, notifications, response) **without signing in**. The token is an
     HMAC-SHA256 over the route id + expiry (can't be retargeted or extended); the public
     `auth: public` share route verifies it (constant-time) and the expiry, else shows an
     invalid/expired notice. The public view omits SQL/tests/coverage; the secret is dedicated (not
     the JWT key). Off until the secret is set. Runtime `ShareLinks`; `DocViews.share`; `docs.share`
     provider; `/_tesseraql/docs/share/route` route. **Extended** to also share a **schema table**
     page and the **coverage** dashboard (same opt-in secret): the HMAC binds a per-kind label
     (route/table/coverage) so a link of one kind can't be replayed as another; the public coverage
     view withholds the per-test failure detail and the public table view drops bearer-gated nav.
     `ShareLinks.mintTable`/`mintCoverage`; `DocViews.shareTable`/`shareCoverage`; `docs.shareTable`/
     `docs.shareCoverage` providers; `/_tesseraql/docs/share/table` + `/.../share/coverage` routes.
   - **SQL&rarr;table dependency graph** — *in progress* (v3.1, the slice deferred from portal v3):
     the route reference page now lists the **tables a route's SQL reads from and writes to**,
     inferred from the bound 2-way SQL by a new dependency-free `SqlTableReferences` extractor
     (`tesseraql-core`) — a lexical heuristic, not a full parser: `FROM`/`JOIN`/`USING` are reads,
     `INSERT INTO`/`UPDATE`/`DELETE FROM`/`MERGE INTO` are writes, with comments, directives, CTE
     names, and derived-table subqueries skipped. A table the `schema` goal introspected cross-links
     to its table page; otherwise it stays plain text. Computed live from the spec's SQL text, so no
     `spec.json` change. `DocService.tableLinks`; `DocViews.route` data-dependency projection. The
     reverse direction also ships: the schema **table** page has a *Used by routes* card listing the
     routes that read/write it (cached reverse index `DocService.routesForTable`; the public
     shared-table view omits it), so the graph is navigable both ways. The DB-free DDL parser the
     v3.1 note also mentioned stays deferred — `schema.json` already gives table structure from live
     introspection, so the dependency graph (SQL side) is the valuable half. *Possible follow-on:* a
     single dependency-overview page (the full route&times;table matrix).
   - **API spec diff / changelog** — *done*: the Export page shows **what changed** in the API since a
     captured baseline. When an OpenAPI baseline sidecar is present
     (`.tesseraql/docs/openapi.baseline.json` — the operator copies a released `openapi.json` there),
     a new canonical `OpenApiDiff` engine (`tesseraql-yaml`) diffs the current generated OpenAPI
     against it by method+path and the page lists the operations added/removed/changed (with the
     per-operation parameter/body/response/security differences); added/changed entries link to their
     route page. Off until a baseline is captured; a corrupt baseline degrades to a note.
     `DocService.apiChangelog`; `DocViews.export` changelog projection.

9. **Coverage trend depth** — *done*: the run-history ring is no longer fixed at 20 runs — a
   non-positive `tesseraql.historyLimit` (`report` goal) / `--history-limit` (`tesseraql test
   --report`) keeps the full history, so the trend spans far more than the former cap. The trend
   panel shows its depth (run count + retained date span) instead of the hard-coded "last 20 runs"
   note. `ReportHistory.append` treats a non-positive cap as unbounded; `DocViews.trend` adds the span.
   - **Coverage regression gate** — *done* (net-new, category 3): beyond the absolute coverage gate,
     the build can fail when SQL coverage drops against the **previous run**. The `report` goal
     compares this run's aggregate line/branch coverage to the most recent `history.json` entry and,
     with `tesseraql.failOnCoverageRegression` (tolerance `tesseraql.coverageRegressionTolerance`,
     points), fails the build on a regression; `tesseraql test --report --fail-on-regression` exits
     `2`. A regression is always logged. New pure `CoverageRegression` (coverage-core) + the
     `ReportRegression` adapter (report). Needs `history.json` to persist across runs for a baseline.

### G. Studio copilot

10. An MCP-driven "describe → draft → preview → apply" assist — *done* (first slice): the protocol
    core gains **MCP prompts** (the third primitive: `prompts/list` / `prompts/get`, `McpPrompt` /
    `McpPromptResult`), and the dev-tool MCP server offers a `studio_copilot` prompt (write mode
    only) that turns a plain-language `task` (+ optional `table`) into guidance steering the
    connecting agent's model through the existing tools (orient → draft → preview → lint/test →
    apply). The "describe" entry point needs **no in-app model**: TesseraQL ships the workflow, the
    agent's own model reasons, and every step stays a separately-gated tool call — the
    architecturally-correct reading of decision point 4 (the MCP loop, not an embedded LLM, is the AI
    surface). Phase 24's draft/preview/apply tools already existed; this completes the loop. Deeper,
    in-product copilot UX (e.g. a Studio-embedded chat) would still be a separate, larger bet.
    - **App-declared prompts** — *done* (follow-on): an app can declare its own MCP prompt as a
      `mcp/*.yml` `kind: prompt` document (a parameterized message template rendered from a colocated
      Thymeleaf TEXT file against the supplied arguments), served at the runtime `/_tesseraql/mcp`
      endpoint alongside its tools/resources/UI — the application-side counterpart of the dev-tool
      `studio_copilot` prompt, still with no embedded LLM. `PromptFile` + `AppManifest.prompts()`;
      `ManifestLoader` parses `kind: prompt`; `AppMcpServer` registers it and renders the template.

### H. Migration authoring (net-new, category 4)

11. **Create migrations from Studio** — *done* (first slice): a **New migration** page (linked from
    the explorer when editable) creates a Flyway migration under `db/…/migration` — a **versioned**
    one auto-numbered `V<n>` (plain sequential, no zero-padding; ordered numerically) or a
    **repeatable** `R__<name>` (views/functions). Targets a chosen datasource + optional vendor
    overlay, writes the DDL through the same gated/audited path as scaffolding (read-only switch +
    per-role `editRoles` + audit trail), refuses to overwrite unless forced, and links the result to
    the source editor. (When this slice shipped the new file needed a restart + migrate; roadmap
    Phase 42 added **Migrate now** — the created page applies pending migrations to the dev
    datasource on demand.) The UI notes Flyway rollback is fix-forward (free edition has no undo). `StudioService.createMigration` / `nextMigrationVersion`;
    `studio.migration.new` / `studio.migration.create`; `/_tesseraql/studio/ui/migration`.
    - **Sandbox dry-run** — *done* (slice 2): a migration file's source editor offers a **Dry-run**
      action that runs the DDL (the live editor buffer) against the dev datasource in a sandboxed,
      auto-rollback transaction — it applies then rolls back, surfacing "applies cleanly" or the DB
      error before the next migrate. **Postgres only** (transactional DDL); other dialects auto-commit
      DDL so it is declined. Gated like the test runner, reusing `SandboxDataSource`.
      `StudioService.dryRunMigration`/`DdlDryRun`/`isMigrationPath`; `StudioTestService.dryRunDdl`;
      `studio.migration.dryRun`; `/_tesseraql/studio/ui/dry-run`.
    - **Form-driven DDL builder** — *done* (slice 3): a **DDL builder** on the New migration page
      generates standard DDL for **add column** and **create index** from structured form input (with
      a conventional `<table>_<cols>_idx` default index name) and drops it into the DDL field to review
      — so the author doesn't hand-write the syntax. A forgiving helper (rejects only an empty required
      field or an embedded `;`), not a validator. New pure `MigrationDdl`; `studio.migration.build`;
      `/_tesseraql/studio/ui/migration/build`.
    - **Schema-populated builder inputs** — *done* (follow-on): the builder's **Table** field is a
      dropdown of the introspected tables (`schema.json` overlay) and the create-index **Columns**
      field autocompletes from the chosen table's columns (htmx cascade on table change); the
      add-column **Type** field offers a common-types datalist. Degrades to free-text when no schema
      overlay. `DocService.tableNames`/`columnNames`; `studio.migration.columns`;
      `/_tesseraql/studio/ui/migration/columns`.
    - **Create-table builder** — *done* (follow-on): the DDL builder gains a **Create table** form —
      a table name, a *columns* textarea (one `name type [modifiers]` per line, emitted verbatim), and
      an optional comma-separated *primary key* — generating `CREATE TABLE … (…[, PRIMARY KEY (…)]);`.
      The one-definition-per-line textarea handles a variable column count in plain HTML.
      `MigrationDdl.createTable`; a `create-table` case in `studio.migration.build`.
    - **Schema-diff generation** — *done* (final slice): with a schema **baseline** sidecar
      (`.tesseraql/docs/schema.baseline.json`), the New migration page generates the migration DDL
      transforming the baseline into the current schema — to capture direct database changes back into
      a migration. The `SchemaDiff` engine makes a table/column added since the baseline a real
      `CREATE TABLE`/`ALTER … ADD COLUMN`, and a destructive change (removed table/column, type
      change) a commented-out line to review (additive-and-safe). `DocService.schemaDiffDdl`;
      `studio.migration.diff`; `/_tesseraql/studio/ui/migration/diff`. **Migration authoring is now
      complete** (create V/R + dry-run + DDL builder incl. add-column/create-index/create-table with
      schema-populated inputs + schema-diff). Command `U` (undo) stays out (paid Flyway feature; not
      modeled).

12. **2-way SQL builder** — *done* (net-new): a **SQL builder** page (linked from the explorer when
    editable) generates a route's `select`/`insert`/`update`/`delete` 2-way SQL for an introspected
    table + operation — with the bind directives written (`/* params.id */ 0`, runnable as plain SQL)
    — to copy into a route's `.sql` file. Schema-driven (columns + primary key from the overlay;
    identity columns skipped on insert; dummy literal typed per column); `where` binds from `params`,
    value binds from `body`. New pure `SqlBuilder` + `DocService.tableByName`;
    `studio.sqlBuilder.new`/`studio.sqlBuilder.build`; `/_tesseraql/studio/ui/sql-builder`.
    - **By-column filter** — *done* (follow-on): a **select-by-column** operation + a **Filter column**
      dropdown cascade-loaded from the chosen table's columns (htmx on table change) generates
      `select … where <col> = /* <col> */ <dummy>` on any column, the bind typed from it.
      `studio.sqlBuilder.columns`; `/_tesseraql/studio/ui/sql-builder/columns`.
    - **IN-list & optional (`/*%if*/`) filters; corrected bind style** — *done* (follow-on):
      **select by column (in list)** (`where <col> in /* <col> */ (<dummy>)`) and **(optional)**
      (`where 1 = 1 /*%if <col> != null */ and <col> = /* <col> */ <dummy> /*%end*/`). Binds now use
      the **param name** (`/* id */`, resolved against `sql.params` at render — the runtime renders
      against the resolved binds, not request namespaces) and the snippet is prefixed with a
      `-- sql.params` comment listing each mapping, so it is complete and correct.
    - **In-editor insertion** — *done* (follow-on): editing a route SQL file (`web/**/*.sql`) offers
      the SQL builder inline (`StudioViews.source` `isRouteSql`; the `studio.source` provider fills the
      table dropdown), and its *Append to editor* button drops the generated snippet straight into the
      editor textarea (htmx `beforeend`, so existing content is kept) instead of copying from the
      standalone page. Reuses the build/columns endpoints. **The 2-way SQL builder is now complete.**

## Recommended next

**A1, A2, B3, C4, D5, D6, and E are all done** (see the per-section notes above): rendered preview
incl. live data + JSON masking + PDF; Run tests for every declarative case kind on routes and jobs;
scaffold-from-explorer (preview/apply/new route); the filterable explorer tree; draft conflict
detection + overview; the audit trail + per-role edit permission; and editor live highlighting
(built-in grammars + a `tql-sql` 2-way grammar, hc 0.1.5 / #264). **F8 (docs export/share) is done** —
API-spec export (OpenAPI + htmx contract), a printable route-catalog PDF, and opt-in signed shareable
links. **F9 (coverage-trend depth) is done** — the run-history ring can keep the full history and the
trend shows its run-count and date span. **G (Studio copilot) — first slice done**: MCP prompts plus
the `studio_copilot` describe→draft→preview→apply prompt, the architecturally-correct reading of
decision point 4 (the MCP loop, not an in-app LLM, is the AI surface). Every A–G backlog item now has
shipped at least its core slice. Anything further is net-new: file new Studio DX ideas here as they
arise (e.g. a Studio-embedded copilot UX, multi-binding live render, confirm-diff-before-every-apply).

## Track H — Studio platform UX

A cross-cutting UX track from a full design review (2026-06). The per-feature work (A–G) is solid,
but the platform-level *journey* lags the individual pages: navigation/wayfinding, async feedback,
and information density. Each slice ships as its own PR (CI green, squash-merge), in the recommended
order below (P0 → P1 → P2).

- [x] **H2 — Shared loading indicators** (P0) — *done*: no template used `hx-indicator`/`aria-busy`,
  so preview/render/dry-run/run-tests/scaffold/migration/sql-builder/search gave no "working" cue on
  slow DB ops (it read as a hang). Added an htmx-native `htmx-indicator` affordance (a reusable
  `tql/shell :: busy(label)` fragment) plus `hx-disabled-elt` to every async action. CSP allows
  `style-src 'unsafe-inline'`, so htmx's injected indicator style applies with no custom CSS/JS.
- [x] **H1 — Studio sidebar nav** (P0) — *done*: Studio pages used the shell's `page(...)` form,
  which renders only the 3-link system nav — there was no Studio section nav (the explorer header
  link-cluster was the only wayfinding). Added a `tql/shell :: studio-page(...)` form that mounts a
  `studio-nav` sidebar (Explorer, Docs, Coverage, Schema, Export, Scaffold, Migration, SQL builder,
  Drafts, Audit, Wizards, then the system apps); the 20 authenticated `studio/ui/**` pages use it
  (the 3 public share views keep the plain `page(...)`). `tesseraql.js` already sets
  `aria-current="page"` on the deepest matching link, so the current section highlights. The nav is
  unconditional (editor-only sections render their own disabled state — honest wayfinding); the
  explorer header dropped its now-duplicated link cluster, keeping the mode badge. A dedicated
  breadcrumb component is folded into H4 (detail-page in-page nav), where it pairs with the route /
  table jump-nav; today the sidebar current-item highlight + the existing header back-links cover it.
- [x] **H3 — Source editor restructure** (P1) — *done*: `source.html` stacked 9+ always-open panels
  in one card, so the page was overwhelming and the primary actions sat far below the preview output.
  Every secondary tool is now a uniform collapsible `<details class="hc-disclosure">` panel
  (Rendered preview / Compare / Dry-run / Tests / SQL builder) — Rendered preview stays open as the
  primary feedback, the on-demand tools collapse — so the page is compact and Save/Apply/Discard are
  within reach without scrolling past everything. Pure `<details>` (CSP-safe, no JS/CSS; hc has no
  tabs component). The literal *sticky action bar* is unneeded now the page is short, so it is
  deferred; the inspect-then-commit order (tools above Apply) is preserved, keeping the
  "review the compare panel above" confirm wording valid.
- [x] **H4 — Detail-page in-page nav + breadcrumbs** (P1) — *done*: the route reference (8+ sections)
  and the table reference were long single-column scrolls with no in-page wayfinding. Each detail
  page now carries a **breadcrumb** in the header (Docs › ‹id› / Schema › ‹table›, replacing the ad-hoc
  back-link) and an **"On this page" jump nav** of native `#anchor` links to each *present* section
  (the section `<section>`s got `id`s; the jump links share each section's `th:if`, so only real
  anchors are offered). Pure HTML anchors — CSP-safe, no JS/CSS. (This also delivers the breadcrumb
  deferred from H1.)
- [x] **H5 — Table filter** (P1) — *done*: the audit trail and the drafts list were dense tables with
  no way to narrow them (audit grows unbounded). Both now carry the explorer's live-filter pattern
  (an htmx `hx-get` that re-selects a swappable `#…-table` region, keeping focus on the input). Audit
  filters **server-side over the whole log** before the newest-200 window applies, so a search reaches
  older actions; the window cap is now stated (`atLimit`), not silent. Drafts filters its (uncapped)
  list in the view. Docs already had a search box. (`auditEntries(limit, query)`,
  `StudioViews.audit/drafts(…, query)`, `q` input on both routes.) True offset paging is deferred —
  the 200-cap + whole-log filter covers the practical need.
- [x] **H6 — Copy buttons on share URLs** (P1) — *done*: the read-only share-URL fields (route,
  table, coverage) forced a manual select+copy. Each now has a **Copy** button driven by a small
  `[data-copy]` behavior in `tesseraql.js` (copies the named element's value via the Clipboard API,
  flips the label to "Copied" briefly). Copy needs JS and the strict CSP forbids inline handlers, so
  it lives in the shared app bootstrap — a candidate to upstream into the hc kit (rule 11; hc 0.1.5
  ships no copy behavior). Secure-context only; a harmless no-op where the Clipboard API is absent.
- [x] **H7 — Wizard clarity** (P2) — *done*: the identity-provider wizards threw jargon (ACS URL,
  NameID, OID attributes, SCIM outbound/token, realm type) at the user with no explanation, and the
  index gave no "which one, in what order?" guidance. The index now describes each wizard and says to
  start with the identity realm; the jargony fields carry concise inline `hc-field__message` help
  (what the field is / where it's registered / when it applies). A literal multi-step *stepper* does
  not fit these single-page forms, so it is dropped in favour of the help; the required/optional
  convention (unmarked = required, "(optional)" suffix) is already consistent.
- [x] **H8 — Correctness + search polish** (P2) — *done*: fixed the stale `sql-builder.html` intro
  (it still showed `/* params.id */ 0` and "values from body" — both wrong since #153/#154; now
  `/* id */ 0`, "each directive names a bind … each binds from `params`"). The docs search lifts its
  query operators out of the placeholder into a visible hint (`status:passing|failing`,
  `coverage:covered|untested`) and the results fragment now leads with a result count
  (`DocViews.searchResults` exposes `count`). Moving focus to the results after a swap was
  intentionally dropped — for the type-ahead search/filter inputs it would interrupt typing; the
  existing `aria-live` regions already announce updates.

**Track H complete (H1–H8).** The Studio platform-UX review is fully addressed: section nav +
breadcrumbs, async loading feedback, a de-cluttered editor, in-page detail nav, table filtering,
share-URL copy, clearer wizards, and the correctness/search polish.

## Track I — adopt off-the-shelf hc components (rule 11)

A follow-up: several Track-H affordances were hand-rolled before confirming the hc kit already ships
them (the components are CSS-only, so they were missed when only the behaviors bundle was searched).
Retire the hand-rolled versions in favour of the blessed components.

- [x] **I1 — `hc-spinner` + `hc-breadcrumb`** — *done*: the `tql/shell :: busy` loading affordance now
  renders the kit's `hc-spinner` (CSS-only, reduced-motion aware) with a contextual label instead of
  a bare "Working…" text fade; the route/table breadcrumbs use `hc-breadcrumb` (semantic `ol`,
  CSS-injected separators) instead of a hand-built `hc-cluster` with a literal `›`. Both ship in hc
  0.1.5 already — no version bump. (H2/H4 retired their hand-rolled markup.)
- [x] **I2 — sortable tables via `hc-datagrid`** (route catalog) — *done* (an earlier note wrongly
  deferred this as "CSP-blocked"; that was incorrect). `hc-datagrid` is deliberately **not** a
  client-side sort engine, but it does **not need JS to be adoptable under our CSP**: (1) the sort
  arrow is pure CSS keyed off `aria-sort` (`.hc-datagrid__headcell[data-sortable][aria-sort=…]:after`),
  and (2) `datagrid.js`'s `onSortClick` does **not** `preventDefault`, so a nested header `<a href>`
  navigates normally. So the route catalog (`docs.html`) is an hc-datagrid with **server-driven**
  sort: each header is a link to `?sort=<col>&dir=<flip>`, the server sorts the rows and sets
  `aria-sort` on the active column, and the kit renders the arrow — no JS, no `hx-vals='js:'`,
  CSP-clean. `DocViews.index(…, sort, dir)` does the sort + emits the per-column `sortHref`/`ariaSort`.
  Extended to the **schema table list** (`schema.html` — each datasource's tables sort by
  name/type/columns/FKs via `DocViews.schema(…, sort, dir)`, sharing the `putSortLinks` helper) and
  the **audit trail** (`audit.html` — sort by When/Actor/Action/Target via
  `StudioService.auditPage(query, sort, dir, page, size)`, the whole filtered log sorted before
  paging). Audit composes all three: a **sort** link carries the filter `q` and resets the page; a
  **page** link carries `q` + sort; the **filter** input keeps the sort across an htmx re-filter via a
  static-JSON `hx-vals` (CSP-clean, not `hx-vals='js:'`). All three Studio sortable tables now use the
  same server-driven hc-datagrid pattern.
- [x] **I3 — `hc-pagination` for the audit trail** (P2) — *done*: H5 capped the trail at the newest
  200; now the whole log is navigable. `StudioService.auditPage(query, page, size)` returns one
  50-entry page (newest first) of the filtered log plus the total; `StudioViews.audit(AuditPage, q)`
  adds the pagination coordinates and the page renders an `hc-pagination` nav (plain styled `<a>`
  links — no JS, CSP-clean; disabled prev/next render as a non-link span). The filter and paging
  compose: filtering re-renders `#audit-table` at page 1 (htmx), paging is a full-page nav that keeps
  the query. Out-of-range pages are graceful (empty slice, still 200).
- [x] **I4 — `hc-tooltip` for the test-result detail** (P2) — *done*: the route reference's pass/fail
  badge carried its failure message in a `title=` tooltip, which screen-reader and keyboard users
  can't reach. It now uses `hc-tooltip` (a sibling `.hc-tooltip` element referenced by
  `aria-describedby`, shown on hover + keyboard focus, dismissible with Escape; the kit's
  auto-installed behavior). The only other `title=` in Studio templates are legitimate `<iframe>`
  accessible names, left as-is. (The wizard jargon is already inline help from H7, so no tooltip
  there.)
- [ ] **I5 — `hc-toast`**: transient feedback (copy "Copied", save/apply success) via the toast
  region instead of mutating button text / inline alerts.

### Genuine hc gaps — file upstream briefs (rule 11)

Confirmed absent from hc 0.1.5 (CSS **and** behaviors): a **copy-to-clipboard** behavior (H6's
`[data-copy]` in `tesseraql.js` is the local stand-in — upstream as `data-hc-copy`); a **scrollspy /
in-page-TOC** behavior (the H4 "On this page" anchors don't highlight the current section on scroll);
and **active-nav marking by URL** (the `aria-current` logic in `tesseraql.js` — `hc-shell`/`hc-navmenu`
don't own it).

Drafted and **filed upstream** as [hc-briefs.md](hc-briefs.md) → `ingcreators/hypermedia-components`
issues #270 (`data-hc-copy`), #271 (`hc-toc` + `data-hc-spy`), #272 (`data-hc-nav-current`), each
with the proposed markup, the CSP/a11y constraints, and the TesseraQL stand-in to retire on
adoption.

## Track J — Studio authoring completion (roadmap Phase 43)

Queued from the 2026-07-03 low-code gap review ([roadmap.md](roadmap.md) Horizon 8); each item
ships as its own slice once the phase opens:

- [x] **J1 — form-driven route editor** — *done*: a **Route form** page (linked from the source
  editor on any `web/**/<method>.yml`) renders the governed fields — recipe, `security.auth`,
  `security.policy` (datalist from the app's policies), CSRF, and the `input:` block as
  structured rows (name/type/required/min/max/maxLength/minLength/pattern/enum) — parsed from
  the pending draft when one exists, else the served source. Saving mutates the document
  **tree** (unknown keys and unmanaged field attributes like `writable`/`mask` survive; comments
  and hand formatting do not — the page says so) and lands a **draft** through the normal
  preview/diff/apply flow, so the text editor stays the escape hatch and apply serves
  immediately (Phase 42). `StudioService.routeForm`/`routeFormSave` (rejects documents that no
  longer parse as a route; `TQL-STUDIO-4230`); `studio.routeForm.view`/`.save` providers;
  `/ui/route-form` page.
- [x] **J2 — connector/SSO authoring** — *done*: a **Connectors** page (explorer chrome) shows
  and edits the managed connector config through the gated overlay-write path — egress
  allow-lists for `http.outbound`/`connectors.poll` (adds/removes write the FULL effective
  list, since deep-merge replaces lists; always behind an explicit confirm, `TQL-STUDIO-4232`
  → 422 without it), outbound/poll credentials (bearer/basic/header) and inbound webhook
  verifiers — where every secret-carrying field must be a secret *reference*
  (`${secret.env.NAME}`, no literal fallback; `TQL-STUDIO-4231` → 400 otherwise) and displayed
  values are redacted (`redactedReference`). The four IAM wizards (OIDC/SAML/SCIM/identity)
  gained **Write to config overlay** beside the snippet download (`formaction` to
  `wizard/<x>/apply`), landing `tesseraql.oidc.*`/`saml.*`/`scim.*`/`identity.*` in
  `config/overlay.yml` with the same reference-only rule. All of it edit-gated, audited, and
  honestly restart-bound (these sections load at boot; the pages say so).
  `StudioService.writeOverlaySection`/`updateEgressHosts`/`writeWebhookVerifier`/
  `writeConnectorCredential`/`connectorsView`; `studio.connectors.*` + `studio.wizard.*.apply`
  providers; `/ui/connectors` page.
- [x] **J3 — test recorder** — *done*: a successful API-console invocation of a query route
  offers **Save as test case** on the result fragment. Recording reverse-maps the sent query/
  body onto the route's `sql.params` (unresolved params are omitted, matching the live
  request's conditional SQL), captures the sandbox row count as `expect.rowCount` when the
  test runner is enabled (the case passes by construction), and appends the `sql:` case to
  `tests/studio-recorded-test.yml` (duplicate names get a numeric suffix; audited). The
  recorded case runs in Studio's per-route test runner and in CI exactly like a hand-written
  one. v1 scope: query routes with a bound SQL file and no path parameters — anything else
  states why it is not recordable (`TQL-STUDIO-4233` → 400).
  `StudioService.recordability`/`recordedCaseParams`/`recordedSqlFile`/`appendRecordedTest`;
  `StudioTestService.sandboxRowCount`; `studio.tryRecord` provider; `/ui/try/record` route.
- [ ] **J4 — data-browser row edit**: PK-scoped single-row edit via a generated command,
  under audit + `editRoles` + confirm.
- [ ] **J5 — authoring feedback**: deepen the shipped JSON Schema and wire it into scaffolded
  repos (`.vscode` association); lint findings gain line/column.

The Studio-embedded copilot chat is roadmap **Phase 44** (an MCP client over the existing
gated tools), a separate phase rather than a Track J slice.
