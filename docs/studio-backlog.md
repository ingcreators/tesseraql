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
- **Rendered preview against sample data (A1, slice 1)** — a template file (`.html`/`.tpl`)
  renders against a sample model rather than only validating against an empty one, and the
  editor shows the actual output two ways: the generated HTML/text, hc-code highlighted, and
  (for HTML) a sandboxed `iframe` visual preview styled with the hc stylesheet. The sample
  model is YAML/JSON typed in the editor, prefilled from a colocated `<name>.sample.yml`
  fixture (blank falls back to the fixture). `StudioService.render`/`sampleModel`, the
  `studio.render` provider, the `POST /_tesseraql/studio/render` JSON endpoint, and the
  `/_tesseraql/studio/ui/render` editor fragment; the source page CSP gains `frame-src 'self'`.

Upstream Hypermedia Components briefs filed and adopted: `hc-code` (read-only block,
gutter, diff), editable `hc-code`, `hc-sparkline`, and read-only syntax highlighting
(issues #253–256, #261). The editable live-highlight overlay is **filed and pending**
(hc issue #264 — see Blocked below).

## Remaining (prioritized)

### A. Tighten the edit → verify loop (highest value, extends what shipped)

1. **Rendered preview against sample/real data** — *slice 1 shipped* (see Shipped): a
   **template file** renders against a sample-data fixture and shows the actual HTML/text
   output plus a sandboxed visual `iframe`. Remaining slices:
   - **Route-context render** — render through a *route* `.yml`'s `response.html.model`
     (resolve the model expressions against a fixture context of `params`/`sql.rows`/…),
     so editing the route, not just the template, previews real output.
   - **More output kinds** — JSON (`query-json`), PDF (the Phase 21 codec), and email (the
     Phase 20 notification template engine) previews, beyond HTML/text.
   - **Real bound params** — optionally execute the route's SQL against a dev datasource to
     populate the fixture (overlaps A2; keep behind the same sandbox guard).
2. **Run a route's declarative suite from Studio** — a "run tests now" action on the
   route doc with inline results, instead of edit → apply → restart → CI. Needs a
   sandboxed execution path (read-only/query, dev datasource, row/time caps). Ties to
   milestone M7 ("schema → verified CRUD in ten minutes").

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

**A1 slice 1 (rendered template preview against sample data) is shipped.** Next: finish A1
(route-context render → JSON/PDF/email kinds → real bound params), then **A2
(run-suite-from-Studio)**, then **B3 (scaffold-from-explorer)** toward M7. E waits on hc #264;
G is gated.
