# Declarative views

Design for roadmap Phase 39 (drafted 2026-07-03; resolves decision point 7). Status:
**slice 1 shipped** — the `kind: view` document, `response.html.view`, the list + form
patterns with the L2 override resolver, `TQL-VIEW-33xx` lint, the `view` coverage kind,
and the example gallery's view-backed board page (`examples/user-admin-app/web/users/board`).
Slices 2–4 (detail/relations/slots + eject, scaffold-on-views, dashboards) remain.

## Context and goals

Every TesseraQL surface is declarative — routes, validation, workflows, scopes, menus,
MCP — except the screen. Pages are hand-written Thymeleaf + htmx (~50 lines per
scaffolded form), and a route's `input:` constraints (`required`, `maxLength`, enums)
are duplicated into HTML attributes by hand. Adding a column to a table today means a
migration, a SQL edit, an `input:` edit, *and* HTML surgery on the form, the table
fragment, and the detail page — the HTML being the only step with no builder, no lint,
and no single source of truth.

A **view** makes the rendering declarative: a `kind: view` document describes a list or
form over a route's data, and the runtime renders it through framework-shipped
Hypermedia Components patterns. The route keeps everything it already owns — SQL,
security, validation, response headers; the view only replaces the hand-written
template.

Non-goals: this is not a client-side component model (rendering stays server-side
hypermedia, mandatory rule 11); it does not replace templates (they remain the escape
hatch and the tool for bespoke pages); declarative pagination is Phase 41; dashboards
wait for the upstream hc chart component (Phase 39 slice 4).

## Decision: interpretation, not build-time generation

Decision point 7 asked whether `kind: view` compiles into generated templates at build
time or is interpreted at render time. **Resolved in favour of interpretation:**

1. **Pattern overrides come free.** The customization ladder's L2 (an app shadowing
   `tql/view/form.html`) is pure template-chain resolution at render time — the
   resolver order in `Templates` already models it. A build-time variant would need a
   regeneration step after every override edit, and a stale-artifact failure mode.
2. **Derived columns need the live row shape.** A list view with no explicit
   `columns:` renders the columns the query actually returned, in authored SQL order —
   `select *` plus a migration shows the new column with zero edits. Only a render-time
   view can do that.
3. **It matches the instantly-live direction.** The menu (`config/menu.yml`) moved to
   per-render interpretation so Studio edits show on the next render; views get the
   same behaviour through the existing route hot-reload, with no generated file to
   regenerate or drift.
4. **Reproducibility is preserved, not weakened.** Rendering is a pure function of
   (view document, fragment set, response model) — there is simply no generated
   artifact to keep deterministic. Build-time work remains where it pays: the manifest
   parses and lints every view, and existence/reference checks fail the build.

The generated-artifact benefit the compile option offered — a diffable HTML file — is
covered where it matters by Studio's rendered preview (the route render path shows the
actual output) and by L3 ejection, which *is* deterministic generation on demand.

## The view document

A view is a colocated YAML document referenced by its route. It resolves exactly like a
template — first next to the route, then under the shared `templates/` root, confined
to the app home — and is not itself a route (only HTTP-method-named `*.yml` files under
`web/` are routes; the `*.view.yml` suffix is the convention).

```yaml
# web/items/new/get.yml
version: tesseraql/v1
id: items.new
kind: route
recipe: page
security: { auth: browser, policy: app.read }
response:
  html:
    view: new.view.yml        # instead of template:
```

```yaml
# web/items/new/new.view.yml
version: tesseraql/v1
id: items.new.form
kind: view
view: form                    # list | form (slice 1); detail follows in slice 2
title: view.items.new.title   # message key; literal fallback
action: /items/create         # the command route this form posts to
fields:                       # optional — override only what differs
  - name: note
    widget: textarea
```

```yaml
# web/items/items.view.yml (a list)
version: tesseraql/v1
id: items.list
kind: view
view: list
title: view.items.title
source: sql                   # model key carrying rows (default: sql)
columns:                      # optional — omit to render the query's own columns
  - name: name
    link: /items/{id}
  - name: due_date
    label: view.items.due
```

`response.html.view` and `response.html.template` are mutually exclusive
(`TQL-VIEW-3302`). Everything else on the route — `status`, `headers`, `headersWhen`,
`model` — behaves unchanged.

## Fields derive from the command route: one source of truth

A form view does not redeclare its fields. `action:` names the command route the form
posts to; the compiler resolves that route at build time and derives the field list
from its **`input:` block** — the same declarations `InputBinder` enforces server-side:

| `input:` declaration | derived rendering |
| --- | --- |
| `type: string` | `text` widget (`textarea` opt-in), `maxlength` from `maxLength` |
| `type: integer` / `number` | `number` widget, `min`/`max` attributes |
| `type: boolean` | `checkbox` (with the hidden-false companion the recipe requires) |
| `type: date` / `datetime` | `date` / `datetime-local` widget |
| `enum: [...]` | `select` with the enum options |
| `required: true` | `required` attribute + the label convention |
| `writable: false`, `_csrf` | never rendered |

Adding a field to the command's `input:` block adds it to the form with **zero view
edits**; the HTML constraint attributes can never disagree with the server-side
validation again, because they are the same declaration. A `fields:` list, when
present, selects and orders the fields and merges presentation overrides (label,
widget, and later slice attributes) onto the derived definitions — it cannot invent a
field the action route does not declare (`TQL-VIEW-3304`).

Lists mirror the idea at render time: with no `columns:`, the view renders the result
set's own columns in authored SQL order; `columns:` selects, orders, and decorates
(label, `link` with `{expr}` placeholders resolved per row, format in a later slice).

## Rendering pipeline and the fragment contract

`HtmlResponseRenderer` gains a view branch: when `view:` is set it parses the document
at build time (cached, existence-checked), and at render time assembles a **view
model** `v` and renders the framework entry fragment for the kind through the same
template engine, wrapped in the existing `tql/shell` page (title, the `config/menu.yml`
app menu, content) so a view is a complete page with the app's chrome.

The fragment set ships on the classpath under `tesseraql/templates/tql/view/` and is
the **public rendering contract**:

| fragment | signature | renders |
| --- | --- | --- |
| `tql/view/list.html` | `view(v)` | an `hc-datagrid` table: columns × rows, row links |
| `tql/view/form.html` | `view(v)` | the blessed mutating-form recipe: `hx-post` to `action`, `_csrf`, inline field-errors target, `hx-disabled-elt` + spinner, no-JS fallback post |
| `tql/view/field.html` | `field(f)` | one labelled field; dispatches to `tql/view/field-<widget>.html` when that fragment resolves, else renders the generic input |

`v` carries `{id, kind, title, action, csrf, fields[]|columns[], data, errorsTarget}`;
a field `f` carries `{name, label, widget, required, maxLength, min, max, options,
value, error}`. These shapes and the fragment signatures are **public API**: versioned
with the YAML schema, annotated under the Phase 34 stability contract, and every change
recorded as breaking in the CHANGELOG. The emitted markup follows
[hypermedia-ui.md](hypermedia-ui.md) exactly — the framework-shipped patterns stay
hc-conformant (rule 11); what an *override* emits is the app's own choice, with the
same status as a hand-written template today.

### The customization ladder (from the roadmap)

- **L0 — view options**: keys in the view document. No HTML.
- **L1 — slots** (slice 2): the view declares named insertion points (`header`,
  `actions`, `after-field`) filled by app fragments — the parameterized
  `tql/shell :: shell(...)` pattern applied to views. An unknown slot name is
  `TQL-VIEW-3306`.
- **L2 — pattern overrides**: `Templates` gains one app-override `FileTemplateResolver`
  ordered ahead of the shared classpath resolver, scoped to `tql/view/*`, rooted at the
  app's `templates/` directory, with existence-check fallthrough. Dropping
  `templates/tql/view/form.html` into the app restyles every form; a
  `field-date.html` retargets one widget everywhere; the per-view `template:` key
  points a single view at a custom fragment. Lint checks an override file declares the
  expected `th:fragment` signature (`TQL-VIEW-3307`).
- **L3 — eject**: render the framework fragments once into a real template file
  (deterministic output, stamped with the scaffold checksum header so edit-detection
  applies) and flip the route's `view:` to `template:`. A CLI/Studio action in a later
  slice; until then ejection is "write the template by hand", exactly today's state.

## i18n, security, Studio

- **i18n**: `title` and labels resolve through the app message catalog (the engine's
  `CatalogMessageResolver`), key-first with literal fallback; a derived field with no
  override gets `view.<viewId>.<field>` then a humanized name (`login_id` → "Login
  id"). Locale-aware value formatting composes with Phase 40/41 work.
- **Security**: nothing new. A view renders inside its route's existing
  auth/policy/CSRF; the form fragment emits the `_csrf` field per the recipe; no new
  endpoints appear. (Rendering `response.json.fields`-style output masking into list
  columns is a recorded later extension, not slice 1.)
- **Studio**: the rendered preview already renders routes through the real pipeline, so
  view-backed routes preview (and live-data preview) unchanged; `.view.yml` sources get
  the YAML editor treatment. A form-driven view editor is Phase 43 (Track J1's pattern)
  and the copilot operates on view documents precisely because they are structured
  (Phase 44).

## Machine-checkable surface

Lint family **`TQL-VIEW-33xx`** (next free block after workflow 31xx/32xx):

| code | check |
| --- | --- |
| 3301 | unknown `view:` kind (not `list`/`form`) |
| 3302 | `view:` and `template:` both set, or the view file does not resolve |
| 3303 | a form's `action:` names no route, or the named route declares no `input:` |
| 3304 | a `fields:` entry names an input the action route does not declare |
| 3305 | unknown widget name |
| 3306 | unknown slot name (slice 2) |
| 3307 | an L2 override file lacks the expected `th:fragment` signature |

Coverage kind **`view`**: one item per view document, exercised when a declarative
suite invokes its route. The htmx-contract and OpenAPI generators are unaffected in
slice 1 (views change how HTML is produced, not the HTTP contract).

## Slices

1. **list + form core** — the view document model and loader, the `response.html.view`
   reference, the interpretation renderer, the `tql/view/*` fragment set, the L2
   app-override resolver in `Templates` (day one), `TQL-VIEW-3301..3305/3307` lint, and
   the `view` coverage kind. The example gallery gains one view-backed page as the
   dogfood.
2. **detail + relations + slots** — `view: detail` (labelled value list over one row),
   parent + child-list composition, named slots (L1, `TQL-VIEW-3306`), and the eject
   action.
3. **scaffold on views** — `scaffold crud` emits view documents instead of raw
   templates; the gallery regenerates on views (byte-identical reproducibility check as
   today); the live-search + server-sort list composition moves into the list pattern.
4. **dashboards** — query-backed cards and charts, gated on the upstream Hypermedia
   Components chart brief (the kit ships only `hc-sparkline` today).

## Deferred / open

- **Pagination**: Phase 41's `page:` block will feed `v.data` and a pager slot; the
  list pattern reserves the slot rather than inventing its own paging.
- **Fragment-mode views** (a bare view for an htmx target region, no shell) ride the
  slice-3 list composition.
- **Shared views** (one view document used by several routes) stay colocated-only until
  a concrete need appears; the `templates/` root fallback already covers the common
  case.
- **Write-side field masking** (per-role field visibility on forms) composes with the
  existing `FieldPolicy` machinery in a later phase.
