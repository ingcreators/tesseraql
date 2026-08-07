# Visual page builder

> **Status: design.** This is an internal design document (docs-site `EXCLUDED`);
> user-facing documentation lands in `declarative-views.md`/`notifications.md`-style
> pages as the slices ship. Scope: WYSIWYG editing of hand-owned Thymeleaf page
> templates in Studio, built on `@hypermedia-components/editor-kit`, plus the eject
> ramp that takes a `view:` route to a hand-owned template without leaving Studio.
> This is the campaign the HTML email design (docs/html-email.md) deferred; it lifts
> the mail composer's plumbing (editor-kit WebJar, hidden-content save/render reuse,
> strict eligibility rule) wholesale.

## Motivation

The view customization ladder (docs/declarative-views.md) ends at L3: eject to a
hand-owned Thymeleaf template. From that moment the page is raw text — Studio's
authoring surfaces (route form, view documents, decision/validation builders) all
stop at the template boundary, and `ViewEjector` itself has no Studio UI (CLI
only). Yet the two halves of a real WYSIWYG canvas already exist:

- **Thymeleaf natural templates keep prototype content in the file.** A `th:text`
  element carries placeholder text; a `th:each` row is a real row of markup. Parsed
  as plain DOM — where `th:*` attributes are inert strings — the template *is* its
  own preview.
- **hc keeps component state in HTML attributes**, which is editor-kit's core
  premise: the canvas DOM is the document model, mutations are six undoable
  primitives, `serialize()` strips editor scaffolding. And core 0.1.14's
  `dist/manifest.json` describes all 67 components — `block`, `parts`,
  `dataAttributes`, `attributeValues`, `cssVars` — exactly what a palette and a
  property inspector need, no hand-curated metadata.

The mail composer could not use the canvas-DOM-is-the-document model directly
(fragment invocations render to nothing until the server evaluates them, so it
holds blocks as data attributes). A page template has the opposite nature: the
markup is literal, so the builder gets the full editor-kit architecture — literal
DOM in the canvas, `serialize()` as the exporter.

**The server-render hazard and the iframe.** Template text must never be emitted
where the builder page's own Thymeleaf render would *evaluate* it (the hazard the
mail composer dodged). The full file travels escaped in the hidden save-form
`<textarea>`; the editable region is seeded through an inert `<template>` element
the server fills with `th:utext` — `th:utext` inserts verbatim without processing
`th:*` (the render-preview precedent), the browser's own HTML parser parses it
once into inert template content (nothing executes or loads), and the builder's
JS only *imports* that content into a same-origin `srcdoc` iframe that links
`hc.min.css` + neutral tokens + `tesseraql.css` and is sandboxed **without**
`allow-scripts`. No string-to-HTML conversion happens in script, and a region
containing `</template>` is ineligible (it could break out of the seed). The
canvas renders with real kit styling; editor-kit's `Overlay({frame})` exists
precisely for iframe-hosted canvases, and the drag controller operates on
`root.ownerDocument`, so both work unchanged.

## Design

### D1 — The builder surface (`ui/builder?path=…`)

**Entry point**: an "Edit visually" header button on the source editor page when
the open file is builder-eligible (the `Edit as form` precedent). No separate
pages list — the explorer already navigates to templates.

**Eligibility (the round-trip rule).** `PageBuilder` (tesseraql-studio, the
`MailComposer` sibling) accepts exactly two shapes, checked server-side to decide
the button and re-checked client-side before mounting:

1. **Shell-wrapped page** — optional prelude (doctype, comments — the ejected
   scaffold-checksum header among them), an `<html … th:replace="~{tql/shell ::
   …}">` open tag, exactly one `div#page-content` child, `</html>`. This is what
   `ViewEjector` emits and what studio-authored pages look like. The canvas edits
   the `#page-content` subtree; prelude and the `<html …>` open tag are captured
   **verbatim** from the source text and re-emitted on export — the checksum
   header survives, and no attribute reordering can touch the wrapper.
2. **Bare fragment file** — no `<html>` root element (slot fragments, shared
   partials). The whole content is the canvas.

Anything else — full documents with hand-written `<head>`, files the grammar
can't reproduce byte-safely — opens read-only with the source editor escape
hatch: the same no-lossy-rewrite contract the mail composer established.

**Canvas mechanics** (`page-builder.js`, the `mail-composer.js` sibling):

- `createEditor({root, manifest})` with the core manifest fetched from
  `/assets/vendor/hypermedia-components__core/dist/manifest.json` (same-origin
  fetch; CSP `default-src 'self'` admits it).
- **Selection** — click in the iframe selects the nearest interesting ancestor
  (manifest block, or any element as fallback); `Overlay({mount, frame})` draws
  outlines in the parent-document layer, never inside the canvas.
- **Structure** — droppable regions are structural containers (`#page-content`,
  `hc-stack`, `hc-cluster`, `hc-card`'s body/footer parts) marked
  `data-hc-editor-container` at mount (editor scaffolding — serializers strip
  it). `canAccept` vetoes table internals (`hc-datagrid` rows/cells need
  table-aware moves — deferred), form-control children, and dropping a node into
  itself. Drag to move; Alt+Arrow as the keyboard path; remove via toolbar.
- **Palette** — manifest-driven insertables. v1 curates the starter set (card,
  stack, cluster, field, input, select, button, badge, alert, empty, datagrid
  shell) as small prototype snippets derived from `block` + `parts`; the full
  67-component sweep and hc *recipes* (complete widget markup) are deferred.
- **Inspector** — for the selected element: its manifest block (when matched),
  each `dataAttribute` as a `<select>` over its `attributeValues` enum (plus
  empty = unset, via `setAttribute`/`removeAttribute`), text content editing for
  leaf elements (`setText`, coalesced), and an advanced section listing the
  element's other attributes (`th:*` included) as raw text inputs — expressions
  edited verbatim, never parsed.
- **Export** — shape 1: captured prelude + captured `<html …>` open tag +
  `serialize()`d `#page-content` + `</html>`; shape 2: `serialize()` of the
  canvas root. Into the hidden `content` field → the source editor's `/save`,
  `/apply` (conflict/confirm gates included) and `/render` (sample-model preview)
  reused unchanged, the mail composer recipe verbatim.

**Round-trip normalization.** `DOMParser` + HTML serialization normalizes what it
touches inside the editable region: attribute quoting, entity encoding, tag case.
The wrapper is exempt (verbatim capture); the region's noise is bounded and
visible — the existing Compare tab diffs the draft against the saved source
before apply. Documented, not fought, in v1.

### D2 — The eject ramp

The CLI's `eject-view` orchestration (locate route → resolve view file → parse
spec → derive form fields → `ViewEjector.eject` → `ScaffoldWriter` →
`flipRoute`) moves to a shared helper in `tesseraql-yaml`
(`ViewEjects.eject(appHome, manifest, routePath, force)`), consumed by both the
CLI command (thinned to argument handling) and a new edit-gated
`studio.ejectView` service (confirm required; `force` checkbox when the writer
reports a blocked hand-edited target; `reloader.reload()` after the flip — the
scaffold-apply precedent). Entry point: an "Eject to template…" button on the
source page of a route that declares `response.html.view`, confirming with the
consequence spelled out (the view document stops driving rendering), then
redirecting to the builder on the fresh template.

### D3 — Guards

- `PageBuilderTest`: both shapes accepted (an actual `ViewEjector` output round-trips —
  eject in the test, parse, export, byte-compare wrapper + stable region); mail
  templates and arbitrary documents rejected (builder and mail composer never
  overlap); prelude/checksum-header survival.
- `StudioIntegrationTest`: builder page renders for an eligible template (canvas
  mount + palette present); eject endpoint flips a fixture route and the template
  serves; ineligible file shows the read-only fallback.
- `HcMarkupContractTest` covers the builder's own page markup automatically (it
  is a normal studio template).

## Slices

1. **Builder** — `PageBuilder` + `ui/builder` page + `page-builder.js` + source
   page entry button + guards.
2. **Eject ramp** — shared `ViewEjects` helper + CLI rewire + `studio.ejectView`
   + source-page button + builder handoff + guards.

## Deferred

- **Recipes palette** (hc recipe markup as insertables) and the full component
  sweep — palette v2.
- **Table-aware editing** inside `hc-datagrid` (column add/remove is a schema
  decision, not a DOM move).
- **cssVars theming inspector** (manifest `cssVars` are per-component theme
  hooks; theming stays in `tesseraql.ui.*` / the theme builder).
- **View-document (L2 and below) visual editing** — the YAML view builder is a
  form problem, already served by route-form patterns; the builder starts where
  YAML ends.
