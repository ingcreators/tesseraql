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

A view is a YAML document referenced by its **id**: every `*.view.yml` under `web/` and
`templates/` joins an app-wide registry at load time, and `response.html.view` names the
document's `id` (explicit, or defaulted from the file name — `new.view.yml` → `new`). A
duplicate id is a build error (`TQL-VIEW-3315`); an unresolved id is `TQL-VIEW-3302`.
The files themselves stay colocated with their routes (or under `templates/` when
shared), and a view is not itself a route (only HTTP-method-named `*.yml` files under
`web/` are routes; the `*.view.yml` suffix is the convention).

Because the reference is a name, one document may serve **several routes**. Every
referencing route must declare the sources, search, and sort inputs the document uses
(the per-route lints below), and the document's `v.id`-derived DOM ids and message-key
namespace are shared across those pages.

```yaml
# web/items/new/get.yml
version: tesseraql/v1
id: items.new
kind: route
recipe: page
security: { auth: browser, policy: app.read }
response:
  html:
    view: items.new.form      # the view document's id — instead of template:
```

```yaml
# web/items/new/new.view.yml
version: tesseraql/v1
id: items.new.form
kind: view
recipe: form                  # list | form | detail | dashboard
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
recipe: list
title: view.items.title
source: sql                   # model key carrying rows (default: sql)
columns:                      # optional — omit to render the query's own columns
  - name: name
    link: /items/{id}
  - name: due_date
    label: view.items.due
```

The `recipe:` key names one of the four kinds — `list`, `form`, `detail`, or `dashboard`;
anything else is `TQL-VIEW-3301`. View documents are strict: an unknown key at any
nesting level is a build error (`TQL-VIEW-3314`), never silently dropped.
`response.html.view` and `response.html.template` are
mutually exclusive (`TQL-VIEW-3302`). Everything else on the route — `status`,
`headers`, `headersWhen`, `model` — behaves unchanged.

## List views

The route behind a list is a plain query route with an HTML response. A paginated list
declares `recipe: query-html` — only the query recipes accept a `pagination:` block
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
pagination: { size: 20, count: true }
sql:
  file: items.sql
  mode: query
  params: { q: query.q }
response:
  html:
    view: items.list
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
[live views](realtime.md). A child or panel `source:` can also name one of the route's
[`http:` sources](connectors.md#http-sources-on-query-routes), rendering external API rows
through the same table pattern as SQL rows.

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

A [field domain](declarative-validation.md#field-domains) may carry a `widget:` hint —
"an SKU is a code input", declared once — and the widget precedence is: the per-view
`fields:` override, else the field's declared widget (usually the domain's), else the
type-derived default. A domain's `enum` already flows through the same merge, so an
enum domain doubles as the source of a form's `<select>` options with no extra key.
Presentation hints are never part of the HTTP contract — OpenAPI emission excludes
them. On the read side, a `columns:` or detail `fields:` entry takes an explicit
`domain:` reference (no name-based inference, ever) — the link that brings the
domain's presentation and data-classification knowledge to rendered output.

A form's `action:` resolves `{placeholder}`s per record, and prefills fall back from
camelCase input names to snake_case columns.

## Detail views

`recipe: detail` renders a labelled value list over one row, and composes its route's
named queries as child lists: a `children:` entry names a source that must be one of
the route's `queries:` (`TQL-VIEW-3308`). A detail offers the same `header`/`footer`
slots as a list.

## Views embed views

`children:` and `panels:` accept view references (docs/view-composition.md wave 2b) —
the one entry type the two composition vocabularies were missing:

```yaml
# dashboard — a panel that is a view
panels:
  - { type: stat, source: sql, column: total }
  - { type: view, view: requests.recent }        # embedded list view

# detail — children reference views; inline columns stay as the shorthand
children:
  - { view: requests.history, source: history }
```

The route remains the sole data owner: an embedded view reads the **host route's**
context through its own `source:` (the entry's `source:` overrides it), validated like
any other source (`TQL-VIEW-3308`). Embedding depth is 1 — an embedded view that itself
embeds is `TQL-VIEW-3318`, which also makes self-embedding impossible. The embedded
document renders through its own entry fragment (its card included, per-view
`template:` retargets respected), so an L2 override of its pattern applies inside the
host too.

Route `model:` entries render alongside `v` on view-backed routes; `v` and `views` are
reserved names (`TQL-VIEW-3319`).

### Declarative parts on hand-owned templates

A `template:` route binds view models without owning a view
(docs/view-composition.md wave 2c):

```yaml
response:
  html:
    template: overview.html
    views: [requests.recent]     # each renders into views['<id>']
```

The template inserts `~{tql/view/list :: view(${views['requests.recent']})}` wherever
it likes. This is what makes L3 non-terminal: **ejecting a composite view emits exactly
this shape** — the host layout pins into the template while embedded views stay
declarative and keep deriving from their routes.

## Dashboard views

`recipe: dashboard` renders query-backed `panels:` over the route's results, laid out on
the kit's `hc-grid`:

- `stat` — one value.
- `sparkline` — the kit component.
- `chart` — the kit's [chart recipe](hypermedia-ui.md#charts): the server renders the
  panel's rows as a real `hc-table` inside the `data-hc-chart` figure — the data
  source, the no-JavaScript fallback, and the screen-reader representation in one —
  and the kit's `installChart` draws the Observable Plot SVG in the browser. Without
  JavaScript the table simply stays visible.
- `table` — an embedded table on the shared list pattern.

```yaml
# web/products/dashboard/dashboard.view.yml
version: tesseraql/v1
id: products.dashboard.view
kind: view
recipe: dashboard
title: Inventory dashboard
panels:
  - { type: stat, source: sql, column: products, label: Products }
  - type: chart
    chart: bar-grouped
    source: byCategory          # one of the route's named queries
    title: Stock by category
    x: label                    # the column supplying each mark's label
    yLabel: units
    series:                     # one column per charted series
      - { column: stock, label: In stock }
      - { column: reorder, label: Reorder floor }
```

A `stat` shows the named `column:` of its source's first row. A `chart` plots the
`x:` column against its `series:` — or against the single `y:` column, the one-series
shorthand — across the source's rows:

- `chart:` is the kit vocabulary: `bar`, `line`, `area`, `combo`, `bar-stacked`,
  `bar-grouped`, `scatter` (default `bar`).
- `series:` entries carry `column`, an optional `label` (message-key-first, like every
  label), and — under `chart: combo` only — the `mark` that series draws with
  (`bar`/`line`/`area`).
- `xType:` (`category`/`number`/`date`), `height:`, `legend:`, and `yLabel:` pass
  through as the kit's `data-*` attributes.

The chart scripts — the self-hosted Observable Plot bundle and the framework's
`charts.js` bootstrap — load only on pages where a chart panel renders; the CSP stays
`default-src 'self'`. Chart vocabulary violations are `TQL-VIEW-3313`. Panel sources
validate like children: a panel's `source:` must be `sql` or one of the route's named
`queries:` or [`http:` sources](connectors.md#http-sources-on-query-routes)
(`TQL-VIEW-3308`). Dashboards eject like any other view, with the pinning
preconditions the ladder describes (L3): chart panels need explicit `x:` and
`series:`/`y:`, table panels explicit `columns:`, and a sparkline's `data-max` has no
static equivalent.

### Filtered dashboards

A parameterized dashboard is existing vocabulary composed, not a new key: declare the
filter as a route input, bind it in the panel queries, and put a plain GET form in the
`header` slot — the same declaration validates the value server-side and re-renders
every panel with it:

```yaml
# web/sales/dashboard/get.yml (excerpt)
input:
  from: { type: date, required: false }
queries:
  byRegion:
    file: by-region.sql        # ... where sale_date >= coalesce(/* query.from */ null, ...)
```

```html
<!-- filters.html — filled into the view's header slot -->
<form th:fragment="filters" class="hc-cluster" method="get" action="/sales/dashboard">
  <div class="hc-field">
    <label class="hc-field__label" for="from">From</label>
    <input class="hc-input" type="date" id="from" name="from" th:value="${params.from}">
  </div>
  <button type="submit" class="hc-button" data-variant="primary">Apply</button>
</form>
```

```yaml
# dashboard.view.yml (excerpt)
slots:
  header: filters.html::filters
```

## One URL, both shapes: shell negotiation

Every HTML response negotiates its shell (`response.html.shell`, default `auto`): an
htmx partial request — `HX-Request: true`, minus boosted navigation (`HX-Boosted`) and
history restore, which both expect a full document — receives the bare `#page-content`
region, while direct navigation receives the shell-wrapped page. One URL is therefore
both deep-linkable and an htmx target; the standing workaround — a hand-written
fragment template whose only purpose was "this URL must not return a full page" — is
gone. Negotiated responses carry `Vary: HX-Request`.

- `shell: auto` — the default above.
- `shell: always` — unconditional shell wrapping (the pre-negotiation behavior).
- `shell: never` — always the bare region: an htmx-only endpoint.

Anything else is `TQL-VIEW-3317`. The mechanism is a Thymeleaf markup selector over the
same template, so it applies to view-backed routes and shell-wrapped `template:` pages
(ejected views included) alike; a hand-written bare fragment has no `#page-content`
region and renders whole either way.

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
| `tql/view/form.html` | `view(v)` | the card (title, header slot, not-found state) around the blessed mutating-form recipe: `hx-post` to `action`, `_csrf`, inline field-errors target, `hx-disabled-elt` + spinner, no-JS fallback post |
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
  the template resolving against the view document's own directory first, then under
  `templates/` — never a referencing route's directory, so a shared view resolves the
  same fragments everywhere. An unknown slot name
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
  `columns:`/`fields:` explicitly first; a dashboard's chart panels need `x:` and
  `series:`/`y:`, its table panels explicit `columns:`, and a sparkline's `data-max`
  is dropped (no static equivalent — pin one in the template). Filled slots inline as
  static fragment inserts. The view document stays on disk for reference. A view
  referenced by more than one route refuses to eject (`TQL-VIEW-3316`) — flipping one
  route would silently fork rendering for the others; copy the document under a new id
  and point the route at the copy first. In Studio, the same action
  is the "Eject view to template…" button on a `view:` route's source page (confirmed;
  a hand-edited target blocks until explicitly overwritten), landing on the fresh
  template.

Past L3, the template stays authorable without hand-writing HTML: Studio's **visual
builder** ("Edit visually" on eligible templates — shell-wrapped pages like ejected
views, and bare fragment files) edits the page as a canvas with real kit styling —
select, drag to reorder, insert components, and edit attributes (with `th:*`
expressions verbatim) — previewing against sample data and saving through the same
draft/apply flow as the source editor. The wrapper around the editable region is
preserved byte-for-byte; a template outside those shapes opens read-only with the
source editor as the escape hatch.

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
- **Security**: a view renders inside its route's existing auth/policy/CSRF; the form
  fragment emits the `_csrf` field per the recipe; no new endpoints appear. **HTML
  output masking** (docs/view-composition.md wave 3b): a column's or detail field's
  explicit `domain:` reference carries the domain's `classification`/`mask` into
  rendering — applied with the same `FieldPolicyApplier` (and the same resolution
  order) the JSON renderer uses, so one row can never render masked in JSON and raw in
  HTML. Embedded views' policies apply through the host render.
- **Studio**: the rendered preview already renders routes through the real pipeline, so
  view-backed routes preview (and live-data preview) unchanged; `.view.yml` sources get
  the YAML editor treatment, and the Studio copilot ([copilot](copilot.md)) operates on
  view documents precisely because they are structured. A dedicated form-driven view
  editor is not currently offered.

## Machine-checkable surface

Lint family **`TQL-VIEW-33xx`**:

| code | check |
| --- | --- |
| 3301 | unknown `recipe:` kind (not `list`/`form`/`detail`/`dashboard`) |
| 3302 | `view:` and `template:` both set, the view id does not resolve in the registry, or a slot's `template::fragment` reference does not resolve |
| 3303 | a form's `action:` names no route, or the named route declares no `input:` |
| 3304 | a `fields:` entry names an input the action route does not declare |
| 3305 | unknown widget name |
| 3306 | unknown slot name for the view kind |
| 3307 | an L2 override file lacks the expected `th:fragment` signature |
| 3308 | a `children:` or `panels:` entry names a source the route's `queries:` do not declare |
| 3309 | `search:` names an input the route does not declare |
| 3310 | sortable columns without the route declaring the `sort`/`dir` inputs its SQL applies |
| 3313 | chart-panel vocabulary: unknown `chart:`, `y:` and `series:` together (or neither), `mark:` outside `chart: combo`, a malformed `xType:`/`height:`, or chart keys on a non-chart panel |
| 3314 | unknown key anywhere in a view document — top level, `fields:`, `columns:`, `children:`, `panels:`, `series:` entries; view documents are strict, never silently dropping a key |
| 3315 | duplicate view id — two `*.view.yml` documents declare (or default to) the same `id` |
| 3316 | ejecting a shared view — the view is referenced by more than one route; copy it under a new id and point the route at the copy first |
| 3317 | `response.html.shell` is not `auto`, `always`, or `never` |
| 3318 | an embedded view embeds views itself — embedding depth is 1 |
| 3319 | `response.html.model` declares a reserved view-model name (`v`, `views`) |

Coverage kind **`view`**: one item per view document, exercised when a declarative
suite invokes any route referencing its id — an unreferenced document is declared and
never covered, which is what makes dead view files visible. The htmx-contract and OpenAPI generators are unaffected —
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

- **Write-side field masking** (per-role field visibility on forms) is planned to
  compose with the existing `FieldPolicy` machinery.
