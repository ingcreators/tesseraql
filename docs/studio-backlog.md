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
- **Run a route's declarative SQL tests from Studio (A2, slice 1)** — a route source page gains a
  **Run tests** action that runs the route's read-only `sql` declarative test cases against the dev
  datasource with inline pass/fail. Gated and sandboxed: enabled only when Studio is writable and
  `tesseraql.studio.testRunner.enabled` is set; each case runs through a `SandboxDataSource`
  (read-only connection, statement timeout, row cap, rollback on close), so a query can neither run
  away nor persist a write. Only read-only `sql` cases run; `validate`/`notify`/`http-call`/write
  paths are out of scope. New `StudioTestService` reusing the declarative `TestRunner` +
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
2. **Run a route's declarative suite from Studio** — *slice 1 shipped* (see Shipped): a **Run
   tests** action runs the route's read-only `sql` cases against the dev datasource, sandboxed and
   opt-in. Remaining slices:
   - **More case kinds** — `validate`/`notify`/`http-call` cases (no DB writes, so safe to add) and
     then write/command cases (need a real rollback-per-case sandbox, since multi-statement cases
     fail under the current per-connection rollback).
   - **Live rows into the rendered preview** — *done* (see Shipped): the route render panel's **Use
     live data** toggle runs the route's main `sql` through the sandbox for real rows. Multi-binding
     routes still inject only the main `sql`; `steps`/`queries` live execution is a later extension.

   Ties to milestone M7 ("schema → verified CRUD in ten minutes").

### B. Creation / scaffolding in the UI

3. **New route / file creation and CRUD scaffold from the explorer** — Studio is
   single-file *edit* only; creation and scaffold are CLI-only (Phase 23). Wire
   "new route" and "scaffold CRUD from table" to the v3 schema introspection (the
   schema catalog already lists tables). Note the hot-reload limit: new/removed routes
   need a restart.

### C. Explorer / navigation polish

4. **Explorer tree + filter** — the flat route/job table → a directory tree and a
   filter box (the docs search index exists, but the explorer has none). Low effort.

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

**A1 (rendered preview) is done**, and **A2 slice 1 (run a route's read-only SQL tests from the
editor, sandboxed) is done**. Next: finish A2 (more case kinds → live rows into the preview), then
**B3 (scaffold-from-explorer)** toward M7. A1's PDF preview and JSON field-masking are optional
follow-ups. E waits on hc #264; G is gated.
