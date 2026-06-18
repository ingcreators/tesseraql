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
    template, `query-json` resolves `response.json.body` and pretty-prints it (output-field masking
    `response.json.fields` is not applied in preview).

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
  and which new routes need a restart to be served. Database-free `StudioService.scaffoldPreview`/
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

Upstream Hypermedia Components briefs filed and adopted: `hc-code` (read-only block,
gutter, diff), editable `hc-code`, `hc-sparkline`, and read-only syntax highlighting
(issues #253–256, #261). The editable live-highlight overlay is **filed and pending**
(hc issue #264 — see Blocked below).

## Remaining (prioritized)

### A. Tighten the edit → verify loop (highest value, extends what shipped)

1. **Rendered preview against sample/real data** — ✅ **done** (see Shipped): template files and
   **web routes** (`query-html`/`page` → `response.html.model` + template; `query-json` →
   `response.json.body`) render against a fixture and show HTML/text/JSON output plus a sandboxed
   visual `iframe` for HTML — the "Studio as the center of the edit loop" gate (decision point 4) is
   met. Optional follow-ups, not blockers (pick up opportunistically):
   - **PDF preview** — needs a `tesseraql-pdf` dependency and a binary-friendly surface (a `data:`
     URL / download); the HTML stage that feeds the PDF already previews.
   - **Output-field masking** — apply `response.json.fields` masking in the JSON preview (needs the
     policy engine + a sample principal); today the preview shows the unmasked resolved body.

   The "render against **real** bound params" end — executing the route's SQL through the A2
   sandbox to populate live rows instead of a hand-authored `sql.rows` fixture — is **done** (the
   render panel's **Use live data** toggle; see Shipped). (Email/notification `.html` templates
   already preview via the template-file path: supply `payload`/`event` as the sample.)
2. **Run a route's or job's declarative suite from Studio** — *done* (see Shipped): a **Run tests**
   action runs every declarative case kind covering a route or job — `sql` (read **and write**),
   `validate`, `contract` (through a sandboxed identity service), `notify`, and `http-call` — against
   the dev datasource, sandboxed (auto-rollback) and opt-in.
   - **Live rows into the rendered preview** — *done* (see Shipped): the route render panel's **Use
     live data** toggle runs the route's main `sql` through the sandbox for real rows. Multi-binding
     routes still inject only the main `sql`; `steps`/`queries` live execution is a later extension.

   Ties to milestone M7 ("schema → verified CRUD in ten minutes").

### B. Creation / scaffolding in the UI

3. **New route / file creation and CRUD scaffold from the explorer** — *done* (slices 1–3): Studio
   was single-file *edit* only; it now also **creates**. "Scaffold CRUD from table" (preview + apply)
   and "new route" both ship, wired to the v3 schema introspection. The hot-reload limit stands:
   newly created routes need a restart to be served, surfaced in the UI.
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
     to the source editor) and flags newly written routes the manifest did not declare, which need a
     restart to be served (the hot reloader only swaps existing routes). Database-free
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

5. **Draft robustness** — discard exists (#106), but there is no concurrent-edit
   conflict detection (last-apply-wins), no draft overview, and apply is not gated
   behind the diff. Consider a confirm-diff-before-apply step.
6. **Granular read-only + audit** — read-only is all-or-nothing; add per-role edit
   permission and an audit trail of who applied what when (production hardening).

### E. Editor live highlighting — **blocked on hc #264**

7. Live highlighting of the editable textarea (hc #264, Phase B: an `installCodeEditor`
   `data-lang` overlay + a consumer-pluggable tokenizer so the JS side can classify
   2-way SQL directives as `meta`). Richer HTML token types (`tag`/`attribute`/
   `property`) await the #264 vocabulary decision. When #264 ships: set `data-lang`
   on the editor and register a JS 2-way-SQL tokenizer.

### F. Docs portal

8. **Export / share** — OpenAPI/JSON export, printable docs (reuse the PDF codec),
   per-route shareable links (docs are in-app/bearer-only today).
9. **Coverage trend depth** — relax the "last 20 runs" cap for longer-term trends.

### G. Studio copilot — **gated (roadmap decision point 4)**

10. An MCP-driven "describe → draft → preview → apply" assist. Decision point 4 gates
    deeper Studio-copilot features on the MCP loop proving its worth; the Phase 24 MCP
    write tools (draft/apply through Studio) already exist.

## Recommended next

**A1 (rendered preview, incl. live data) is done**, and **A2 is fully done** — Run tests covers
every declarative case kind (`sql` read/write, `validate`, `contract`, `notify`, `http-call`) for
routes and jobs, sandboxed with auto-rollback. **B3 (scaffold-from-explorer) is fully done**:
preview (slice 1), apply (slice 2), and new blank route (slice 3) all shipped — pick a table, preview
its CRUD slice, and create the files (edit detection + force), or create a single starter route, each
with a restart notice for new routes. **C4 (explorer tree + filter) is done** — the explorer is a
filterable directory tree. Recommended next: **D5 (draft robustness — confirm-diff-before-apply,
concurrent-edit detection)**, or A1's optional PDF preview / JSON field-masking. E waits on hc #264;
G is gated.
