# Declarative views

Every TesseraQL surface is declarative — routes, validation, workflows, scopes, menus,
MCP — and views make the screen declarative too. A **view** is a `kind: view` document
that describes a page — a list, form, detail, or dashboard — over a route's data, and
the runtime renders it through framework-shipped Hypermedia Components patterns. The
route keeps everything it already owns — SQL, security, validation, response headers;
the view only replaces the hand-written template.

Use a view wherever a page follows a standard shape: adding a column to a table becomes
a migration plus a SQL edit, with no HTML surgery, because the rendering derives from
the route's own declarations. Views are not a client-side component model (rendering
stays server-side hypermedia), and they do not replace templates — templates remain the
escape hatch and the tool for bespoke pages. Pagination is its own feature and composes
with list views ([pagination](pagination.md)).

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
view: form                    # list | form | detail | dashboard
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

The `view:` key names one of the four kinds — `list`, `form`, `detail`, or `dashboard`;
anything else is `TQL-VIEW-3301`. `response.html.view` and `response.html.template` are
mutually exclusive (`TQL-VIEW-3302`). Everything else on the route — `status`,
`headers`, `headersWhen`, `model` — behaves unchanged.

## List views

The route behind a list is a plain query route with an HTML response. A paginated list
declares `recipe: query-html` — only the query recipes accept a `page:` block
([pagination](pagination.md)):

```yaml
# web/items/get.yml
version: tesseraql/v1
id: items.page
kind: route
recipe: query-html
security: { auth: browser, policy: app.read }
input:
  q: { type: string, required: false, maxLength: 100 }
  sort: { type: string, required: false }
  dir: { type: string, required: false, enum: [asc, desc] }
page: { size: 20, count: true }
sql:
  file: items.sql
  mode: query
  params: { q: query.q }
response:
  html:
    view: items.view.yml
```

With no `columns:`, a list renders the result set's own columns in authored SQL order —
`select *` plus a migration shows the new column with zero edits. `columns:` selects,
orders, and decorates: `label` overrides the heading, `link` renders a per-row link with
`{expr}` placeholders resolved per row, and `text:`/`link:` columns render a per-row
action button.

Lists also carry search and server-driven sort:

- `search:` renders the pattern's filter box, wired to a declared route input.
- `sortable: true` on a column renders `?sort=&dir=` header links with `aria-sort`; the
  route's enum-gated `sort`/`dir` inputs apply them in SQL.

The wiring is lint-checked (`TQL-VIEW-3309/3310`): the search key must be a declared
input of the route, and sortable columns require the route to declare the `sort` and
`dir` inputs its SQL applies.

On a paginated route, the list renders the kit's `hc-pagination` nav, with links
preserving the search and sort state ([pagination](pagination.md)).

With `refreshOn: <topic>`, the list refreshes itself whenever a command that declares
`emit: <topic>` commits — detail and dashboard views take the same key; see
[live views](realtime.md).

## Form views: fields derive from the command route

A form view does not redeclare its fields. `action:` names the command route the form
posts to; the compiler resolves that route at build time and derives the field list
from its **`input:` block** — the same declarations the input binder enforces
server-side:

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
widget, and other presentation attributes) onto the derived definitions — it cannot
invent a field the action route does not declare (`TQL-VIEW-3304`).

A form's `action:` resolves `{placeholder}`s per record, and prefills fall back from
camelCase input names to snake_case columns.

## Detail views

`view: detail` renders a labelled value list over one row, and composes its route's
named queries as child lists: a `children:` entry names a source that must be one of
the route's `queries:` (`TQL-VIEW-3308`). A detail offers the same `header`/`footer`
slots as a list.

## Dashboard views

`view: dashboard` renders query-backed `panels:` over the route's results, laid out on
the kit's `hc-grid`:

- `stat` — one value.
- `sparkline` — the kit component.
- `chart` — bar/line as deterministic server-rendered SVG wearing the kit's `hc-chart`
  skin: every color a `--hc-chart-*` token, the gridline group colored by
  `[aria-label$=grid]`, no client scripting.
- `table` — an embedded table on the shared list pattern.

```yaml
# web/products/dashboard/dashboard.view.yml
version: tesseraql/v1
id: products.dashboard.view
kind: view
view: dashboard
title: Inventory dashboard
panels:
  - { type: stat, source: sql, column: products, label: Products }
  - type: chart
    kind: bar
    source: byCategory          # one of the route's named queries
    title: Stock by category
    x: label                    # the column supplying each bar's label
    y: value                    # the numeric column charted
```

A `stat` shows the named `column:` of its source's first row; a `chart` plots the
`x:`/`y:` columns across the source's rows. Panel sources validate like children: a
panel's `source:` must be `sql` or one of the route's named queries (`TQL-VIEW-3308`).
Ejection is not offered for dashboards — the SVG is data-dependent.

## Rendering pipeline and the fragment contract

When `view:` is set, the HTML renderer parses the document at build time (cached,
existence-checked), and at render time assembles a **view model** `v` and renders the framework entry fragment for the kind through the same
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
with the YAML schema, annotated under the framework's stability contract, and every
change recorded as breaking in the CHANGELOG. The emitted markup follows
[hypermedia-ui.md](hypermedia-ui.md) exactly — the framework-shipped patterns stay
hc-conformant; what an *override* emits is the app's own choice, with the same status
as a hand-written template today.

### The customization ladder

- **L0 — view options**: keys in the view document. No HTML.
- **L1 — slots**: the view declares named insertion points filled by app fragments —
  the parameterized `tql/shell :: shell(...)` pattern applied to views. A list or
  detail offers `header`/`footer`; a form adds `actions` beside its submit button. A
  slot value is `template::fragment` (compact, so the plain YAML scalar stays legal),
  the template resolving colocated-first then under `templates/`. An unknown slot name
  is `TQL-VIEW-3306`; an unresolved reference is `TQL-VIEW-3302`.
- **L2 — pattern overrides**: the template resolver chain resolves an app override —
  scoped to `tql/view/*`, rooted at the app's `templates/` directory — ahead of the
  shared classpath fragments, with existence-check fallthrough. Dropping `templates/tql/view/form.html` into the app restyles every
  form; a `field-date.html` retargets one widget everywhere; the per-view `template:`
  key points a single view at a custom fragment. Lint checks an override file declares
  the expected `th:fragment` signature (`TQL-VIEW-3307`).
- **L3 — eject**: `tesseraql scaffold eject-view --app . --route web/…/get.yml`
  renders the view's pattern once into a real template (deterministic output, stamped
  with the scaffold checksum so edit detection applies) and flips the route from
  `view:` to `template:`. Ejecting pins the layout — a list/detail must declare its
  `columns:`/`fields:` explicitly first — and filled slots inline as static fragment
  inserts. The view document stays on disk for reference; a Studio surface for the same
  action is not currently offered.

## Scaffolding and examples

`scaffold crud` emits view documents instead of raw templates
([scaffolding](scaffolding.md)): one list route with the pattern's search box and
server-driven sort (no fragment route), forms derived from the command routes, and
slots carrying the New/back/confirmed-delete affordances. The example gallery includes
a view-backed board list + detail (`examples/user-admin-app/web/users/board`).

## i18n, security, Studio

- **i18n**: `title` and labels resolve through the app message catalog, key-first with
  literal fallback; a derived field with no
  override gets `view.<viewId>.<field>` then a humanized name (`login_id` → "Login
  id"). Locale-aware value formatting composes with [declarative
  validation](declarative-validation.md) and [pagination](pagination.md).
- **Security**: nothing new. A view renders inside its route's existing
  auth/policy/CSRF; the form fragment emits the `_csrf` field per the recipe; no new
  endpoints appear. (Rendering `response.json.fields`-style output masking into list
  columns is planned, not currently supported.)
- **Studio**: the rendered preview already renders routes through the real pipeline, so
  view-backed routes preview (and live-data preview) unchanged; `.view.yml` sources get
  the YAML editor treatment, and the Studio copilot ([copilot](copilot.md)) operates on
  view documents precisely because they are structured. A dedicated form-driven view
  editor is not currently offered.

## Machine-checkable surface

Lint family **`TQL-VIEW-33xx`**:

| code | check |
| --- | --- |
| 3301 | unknown `view:` kind (not `list`/`form`/`detail`/`dashboard`) |
| 3302 | `view:` and `template:` both set, the view file does not resolve, or a slot's `template::fragment` reference does not resolve |
| 3303 | a form's `action:` names no route, or the named route declares no `input:` |
| 3304 | a `fields:` entry names an input the action route does not declare |
| 3305 | unknown widget name |
| 3306 | unknown slot name for the view kind |
| 3307 | an L2 override file lacks the expected `th:fragment` signature |
| 3308 | a `children:` or `panels:` entry names a source the route's `queries:` do not declare |
| 3309 | `search:` names an input the route does not declare |
| 3310 | sortable columns without the route declaring the `sort`/`dir` inputs its SQL applies |

Coverage kind **`view`**: one item per view document, exercised when a declarative
suite invokes its route. The htmx-contract and OpenAPI generators are unaffected —
views change how HTML is produced, not the HTTP contract.

## Design notes

Views are **interpreted at render time**, not compiled into generated templates:

1. **Pattern overrides come free.** An app shadowing `tql/view/form.html` (L2) is pure
   template-chain resolution at render time — the template resolver chain already
   models it. A build-time variant would need a regeneration step after every
   override edit, and a stale-artifact failure mode.
2. **Derived columns need the live row shape.** A list view with no explicit `columns:`
   renders the columns the query actually returned, in authored SQL order — only a
   render-time view can do that.
3. **It matches the instantly-live direction.** Like the `config/menu.yml` app menu,
   views are interpreted per render, so edits show on the next render through the
   route hot reload, with no generated file to regenerate or drift.
4. **Reproducibility is preserved, not weakened.** Rendering is a pure function of
   (view document, fragment set, response model) — there is simply no generated
   artifact to keep deterministic. Build-time work remains where it pays: the manifest
   parses and lints every view, and existence/reference checks fail the build.

The one thing compilation would have offered — a diffable HTML file — is covered by
Studio's rendered preview and by L3 ejection, which *is* deterministic generation on
demand.

Not currently supported:

- **Fragment-mode views** — a bare view for an htmx target region, rendered without
  the shell — are planned.
- **Shared views** (one view document used by several routes) stay colocated-only until
  a concrete need appears; the `templates/` root fallback already covers the common
  case.
- **Write-side field masking** (per-role field visibility on forms) is planned to
  compose with the existing `FieldPolicy` machinery.
