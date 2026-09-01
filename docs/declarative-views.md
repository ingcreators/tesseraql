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
sources:
  main:
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
`{column}` placeholders resolved per row, and `text:`/`link:` columns render a per-row
action button. Each placeholder must be one plain column name (`TQL-VIEW-3321`), and the
substituted values are URL-encoded — a key containing `/` or `?` cannot break the href.

Lists also carry search and server-driven sort:

- `search:` renders the pattern's filter box, wired to a declared route input.
- `sortable: true` on a column renders `?sort=&dir=` header links with `aria-sort`; the
  route's enum-gated `sort`/`dir` inputs apply them in SQL.
- A **multi-column sort** declares the input as `type: sort` with a `columns:` allowlist.
  The wire form is one ordered param — `?sort=-ship_date,order_no`, a leading `-` for
  descending — and the framework publishes the validated ORDER BY fragment as
  `params.sortSql` for the authored `/*# order by {sort} */` directive to bind. Header
  links keep working unchanged (a declared `dir` still applies to a bare single column),
  and the grid page's toolbar reads the applied set out ("Sort (2): Ship date ↓, Order
  ↑"). The reorderable sort-list editor is a planned follow-up; until then the URL — and a
  named view preset — is how a multi-sort is applied.

The wiring is lint-checked (`TQL-VIEW-3309/3310`): the search key must be a declared
input of the route, and sortable columns require the route to declare the `sort` and
`dir` inputs its SQL applies.

On a paginated route, the list renders the kit's `hc-pagination` nav, with links
preserving the search, sort and size state ([pagination](pagination.md)).

### The grid page

Every list renders as the operational grid page (docs/list-surface.md, the flip): the
chrome — title, presets, search bar, condition chips, status line, pager — stays put, and
only the grid scrolls. Page links swap the table region in place and push the URL, so
every page state stays a bookmarkable address. On a counted route the status line shows
the absolute window ("21–40 of 56"). Below the desktop breakpoint the frame falls back to
normal page scrolling, and printing renders every fetched row. Regions render only when
the contract declares them, so a minimal list is still a quiet page; apps override the
pattern by shipping `templates/tql/view/list.html`.

### Filters: declared params as chips and a dialog

`filters:` lists declared route inputs — bare names, or `{ name, label }` mappings:

```yaml
filters: [status, { name: priority, label: How urgent }]
```

The grid page renders them twice. Applied conditions become chips on their own line; each
chip's remove control is a real link to the same URL minus that condition, and "Clear all"
keeps the search and sort. A Filters button opens a dialog of the declared inputs — enum
and `codes:` inputs render as selects with an empty "any" choice, dates as date pickers,
exactly as a form field would. Applying navigates like any other search: the result is a
bookmarkable URL, and the page resets to 1. There is no operator language — a param *is* a
condition, and the route's SQL applies it (`/*%if status */ and status = /* status */ 'x'
/*%end*/`). Every filter must name a declared route input (`TQL-VIEW-3323`), and pager,
sort and search navigation all carry the applied filters.

### Named views: `presets:`

A preset is a contract-declared param set rendered as a real link beside the title:

```yaml
presets:
  - name: Open tickets
    params: { status: open }
  - name: High priority
    params: { priority: high, sort: "-created_at" }
```

No storage is involved: selecting a preset navigates, the active one is the preset whose
params the current URL carries, and re-clicking it is the reset. A "Modified" badge marks
an applied filter or search the active preset does not pin — a tweaked view, still
recognizably that view. Sharing a preset is copying the address bar. Preset params must be
declared route inputs or the framework `sort`/`dir`/`size` params (`TQL-VIEW-3324`).
User-created saved views wait for a per-user store; presets are the contract's own.

### Bulk actions: `actions:`

With a declared `key:`, `actions:` renders the selection column and a selection bar:

```yaml
key: id
actions:
  - label: Approve
    action: /requests/approve
    confirm: Approve the selected requests?
```

One form wraps the grid and the bar (the kit's datagrid-bulk-actions recipe): the checked
rows' tokens travel as repeated `ids` fields by native form serialization, and each action
button posts to its own route. The bar stays hidden until rows are checked
(`installDatagridActions`, auto-initialized). On the receiving route, the framework
decodes single-column-key tokens back into bare key values before binding, so a declared
`ids: { type: array, items: { type: integer } }` input receives plain ids — composite-key
tokens pass through as tokens for now. The no-JS submit answers post/redirect/get;
`location: back` lands on the list the selection came from. Every `actions:` entry must
match a POST route (`TQL-VIEW-3325`); tokens prove nothing — the route's own security and
SQL decide what the ids may touch.

### Work queues: `pagination: { strategy: snapshot }`

A work queue wants the opposite of a live re-query: acting on page 1 must not slide rows
up from page 2. Snapshot paging freezes the *membership* at search time while each row's
*state* stays live:

```yaml
pagination: { strategy: snapshot, size: 20, cap: 500 }
```

The search renders every hit's row token as hidden `keys` fields (requires the view's
single-column `key:`). The pager becomes POST buttons that resubmit
the membership plus a page number, and each page fetches live state for its slice only.
The framework decodes the slice into `params.keys`, and the authored SQL binds the
IN-list (`/*%if keys != null */ and t.id in /* keys */(1) /*%end*/`). A row that vanished
since the search renders as a tombstone, so the page arithmetic and the user's count
hold; the status line says "of N (as of search)" deliberately. A search whose hits exceed
`cap:` (default 500) still answers 200 — over-cap is a user state, not an error. The page
renders a reject block where the table would be, asking the user to narrow, and keeps the
search chrome so they can. Never truncate: a truncated queue silently hides items from
every operator. A page fetch posting more keys than the cap is different — the framework
never rendered that many, so it is refused with 422 (`TQL-FIELD-4222`). Reload is a new
search, by design: snapshot pages have no URL and want none. Tokens prove nothing; every
page fetch runs the route's own security and SQL.

### Truncation is visible: the result-cap banner

A plain (non-snapshot) list bounded by `materialize: { maxRows: N, onOverflow: warn }`
used to truncate silently — the log warned, the page pretended the shown rows were
everything. Now the list renders a persistent warning banner naming the shown count (and
the current sort, when one is applied), and the status line hedges the total as "N+
results" — the exact total is the count query the cap exists to avoid. The truncation is
also a fact of the result set: the query's context carries `truncated: true`, so a JSON
body can map the same flag.

### Row identity and returning to the list: `key:` and `location: back`

`key:` names the result columns that identify one row — a single column (`key: id`) or an
ordered list for a composite key (`key: [order_id, line_no]`). Each row then renders a
stable anchor (`id="row-…"`) built from an opaque row token over those values. Every key
column must be present and non-null in each row; a violation is an error (`TQL-VIEW-3322`),
never a silently skipped row.

On the grid page, each row link also carries the list's own URL — conditions, sort, and
page included — as a `_return` parameter, with the acting row's anchor as its fragment.
The form pattern echoes a validated `_return` as a hidden field, and a command route may
declare `location: back` to follow it: the browser lands back on the same list page,
scrolled to the row it acted on. A `_return` that does not stay inside the application is
discarded and the redirect falls back to the root, so the field is never an open-redirect
surface.

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
| `codes: <catalog>` | `select` over the catalog's active codes |
| `lookup:` | the reference lookup field: code entry + hidden id + search dialog |
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

Where that domain names a [code catalog](code-catalogs.md), the column renders the **name**
behind the code rather than the code: on a list, on a detail field, inside a detail's
child table, in a dashboard's table panel, and in the template the page is ejected
into, which calls the catalog rather than carrying today's names as literals. A code
the catalog does not name renders as itself — a missing name does not blank a cell.
Ordering is still the SQL's, so a `sortable:` coded column sorts by code.

Above the catalog size line ([lookups](lookups.md), "Catalog or enrichment"), a field
declares `lookup:` instead of `codes:` — a business master is searched, not listed. The
block names a GET query route by path (`source:`) plus its rows' `code:` and `label:`
columns; the field renders as a visible code input, a hidden id (the only thing the
form submits), a hint carrying the resolved name, and a 🔍 button opening the
synthesized search dialog. Typing a code re-renders the whole field through the
companion route — resolved, unresolved (422, hidden id emptied), or cleared — and a
submitted id is existence-checked again inside the command's transaction. The referenced
route's own `security:` and SQL govern every leg; a domain may carry the whole block, so
"a supplier reference" is declared once. A dangling `source:` is lint `TQL-YAML-1059`
and build failure `TQL-FIELD-4623`; a source row missing a declared column is
`TQL-VIEW-3329` at render.

A form's `action:` resolves `{placeholder}`s per record, and prefills fall back from
camelCase input names to snake_case columns.

## Detail views

`recipe: detail` renders a labelled value list over one row, and composes its route's
named queries as child lists: a `children:` entry names a source that must be one of
the route's `sources:` (`TQL-VIEW-3308`). A detail offers the same `header`/`footer`
slots as a list.

### Workflow transitions: `workflow:`

A detail view over a workflow-governed document opts into the workflow surface
(docs/workflow-surface.md) with one key:

```yaml
kind: view
recipe: detail
workflow: purchase_request
```

The id must name a declared `kind: workflow` document — an unknown id fails the build
(`TQL-VIEW-3327`), and nothing is ever inferred from table names. The page then renders
the lifecycle stepper (`hc-stepper`, states in declaration order, the current one
marked) and a toolbar of exactly the transitions legal for the viewing principal on the
row's current state. Wrong role is absent; wrong state is absent; a transition whose
expression guard says no renders disabled with the guard's declared `message:`. A guard
that needs the transition's own connection — a SQL file, or a `decision.*` read —
renders enabled, and the executor's refusal is the answer. A `join:` transition shows
its stamp progress when the row selects the stamp columns.

The buttons are native submits posting each transition's own synthesized route with the
page as `_return`: a form post answers 303 back here, so the redirected GET re-renders
current truth — a stale post is refused with 409, because the state itself is the
optimistic lock. API callers keep the JSON contract unchanged. Transitions into a
terminal state carry the confirm gate. The view's SQL must select the document's key
column and, in app mode, its state column; a row missing either refuses loudly
(`TQL-VIEW-3328`) instead of rendering a lifecycle that may be wrong.

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
`sources:` entries, whatever arm each names ([`http:` included](connectors.md#http-sources-on-query-routes))
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
sources:
  byRegion:
    sql:
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

When `view:` is set, the HTML renderer parses the document at build time, cached and
existence-checked. At render time it assembles a **view model** `v` and renders the
framework entry fragment for the kind through the same template engine. The result is
wrapped in the existing `tql/shell` page — title, the `config/menu.yml` app menu, content —
so a view is a complete page carrying the app's chrome.

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

Past L3, the template stays authorable without hand-writing HTML. Studio's **visual
builder** offers "Edit visually" on eligible templates: shell-wrapped pages such as ejected
views, and bare fragment files. It edits the page as a canvas with real kit styling — you
select, drag to reorder, insert components, and edit attributes, with `th:*` expressions
kept verbatim. It previews against sample data and saves through the same draft and apply
flow as the source editor. The wrapper around the editable region is
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
| 3308 | a `children:` or `panels:` entry names a source the route's `sources:` do not declare |
| 3309 | `search:` names an input the route does not declare |
| 3310 | sortable columns without the route declaring the `sort`/`dir` inputs its SQL applies |
| 3313 | chart-panel vocabulary: unknown `chart:`, `y:` and `series:` together (or neither), `mark:` outside `chart: combo`, a malformed `xType:`/`height:`, or chart keys on a non-chart panel |
| 3314 | unknown key anywhere in a view document — top level, `fields:`, `columns:`, `children:`, `panels:`, `series:` entries; view documents are strict, never silently dropping a key |
| 3315 | duplicate view id — two `*.view.yml` documents declare (or default to) the same `id` |
| 3316 | ejecting a shared view — the view is referenced by more than one route; copy it under a new id and point the route at the copy first |
| 3317 | `response.html.shell` is not `auto`, `always`, or `never` |
| 3318 | an embedded view embeds views itself — embedding depth is 1 |
| 3319 | `response.html.model` declares a reserved view-model name (`v`, `views`) |
| 3321 | a column `link:` placeholder is not one plain column name — dotted or malformed placeholders render empty at runtime and eject wrong |
| 3322 | a declared `key:` column is null, absent or blank in a result row — a row without its declared identity is a data defect |
| 3323 | a `filters:` entry names an input the route does not declare |
| 3324 | a `presets:` param names an input the route does not declare (framework `sort`/`dir`/`size` excepted) |
| 3325 | an `actions:` entry targets a URL that matches no POST route |
| 3326 | snapshot pagination on a view without a single-column `key:` |

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

## Write-side field policy

Hiding a field must mean the server does not accept it
(docs/view-composition.md wave 4). An `input:` field takes `policy:` — a security
policy the principal must satisfy to supply it:

```yaml
input:
  note:   { type: string, maxLength: 200 }
  salary: { type: integer, policy: hr.write }   # only principals satisfying hr.write
```

Enforcement happens **at the binder**: a failing principal's value follows the route's
readOnly behavior (reject by default, or ignore/warn — and an ignored value is treated
as not supplied, never bound). The derived form omits the field for that principal —
the same declaration drives both, extending the form-derivation principle ("the HTML
constraint and the server validation are the same declaration") to authorization. Like
`required` and `writable`, `policy:` is operational and never accepted inside a
domain. The per-role form stops requiring N command routes. OpenAPI is unchanged —
policy-gated fields remain declared; the contract does not vary by role.

## Next

- [hypermedia-ui.md](hypermedia-ui.md) — the htmx patterns the views render into.
- [realtime.md](realtime.md) — a view that refreshes itself when data changes.
- [pagination.md](pagination.md) — paging a list view.
