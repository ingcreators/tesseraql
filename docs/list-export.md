# Export this filtered set: the list surface kicks off an export, and the job card lands on the grid page

> **Status: designed 2026-09-22, measured against main `8990b1972` (0.19.0-SNAPSHOT). Two
> implementation slices; the user names each move.** This is the named trigger of the
> [hc recipe ledger](hc-recipe-alignment.md)'s async-job row — "the first HTML async kick-off,
> most plausibly the list surface growing an 'export this filtered set' action" — and the
> second consumer of the job card the csv-import campaign built ([csv-import.md](csv-import.md)
> decisions 6 and 8: "the export side inherits the card for free").
>
> **S1** — `exports:` on a list view, judged once by `TQL-VIEW-3331`; the control in the grid
> page's navigation strip with the count it can vouch for, and in both result-cap surfaces as
> their escape hatch; a `query-export` target renders the link, a `file-export` target the
> kick-off form, and the export start leg answers a native form post with the redirect the
> transfer page already serves; the inventory and helpdesk dogfoods: **shipped, #1426** (every
> decision as recommended). Three details the slice settled: the judgement lives in one
> `ViewExports` class both altitudes call, with `RouteCompiler.requireExportTargets` as the
> boot twin and `ViewBinding.of` refusing only an unresolvable target; the policy gate is
> pinned by the renderer test's `permits` predicate rather than the integration test, whose
> routes are public; and the ejector hands the ejected control the route's method through
> `ViewEjects`, so a `file-export` ejects as a one-button form.
>
> **S2** — the card on the page: the htmx kick-off answers 202 and the running card into its
> own region, one shared kick-off answer for imports and exports, `_idempotency` on the
> kick-off form and the idempotency pair on `file-export`, a reclaimed export's card says
> expired, the hypermedia-ui page and the ledger row.

A list page shows one question's answer twenty rows at a time. The rows the user wants in a
spreadsheet are the answer to the *same* question — every page, in the sorted order, under the
same filters — and today the grid page has no control that asks it. The gallery's two CSV
exports are synchronous `query-export` routes no list links to; no gallery route declares
`recipe: file-export` at all; and the asynchronous export answers a browser exactly what it
answers a script: a JSON envelope with a transfer id. The card that turns that id into a
running, done, failed, cancelled or expired state exists since the csv-import campaign and
renders for imports only, because nothing kicks an export off from a page.

This record gives the list view one more declaration, `exports:`, and decides three things the
hc recipe ledger left to the trigger: how the filter, preset, sort and snapshot state of a list
reaches an export route; how the async-job card, its terminal stop and its tombstone are drawn
on a page that swaps its own regions; and how the action stands to the route's security, to the
transfer's readers and to the result cap.

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by this record.
Site-excluded, so the prose lint does not read it; `sync-content.mjs` and `InternalDocsSyncTest`
do.

## What was measured

Readings of the code, the records and the upstream contracts on main `8990b1972`. Nothing here
was run: every mechanism this design composes is pinned by an integration test already
(rows 4, 7, 12), and the first slice's guards are where the composition meets a running
application.

| # | Reading | Result |
| --- | --- | --- |
| 1 | The trigger as written (`docs/hc-recipe-alignment.md:221-241`, and the row at `:72`) | async-job is **Adopted (#1127–#1133)** on the import commit leg; the section keeps its trigger sentence for the list surface and says why: "until then there is nothing for the card to render" on an export. `docs/csv-import.md:606-616` (decision 8): `mountTransferStatus` is shared by `buildFileImport` and `buildFileExport`, the card's done state is direction-aware from the start, "an asynchronous Excel export therefore becomes: kick off, get the card, watch it, download from the done card." Only the kick-off is missing. |
| 2 | What kicks an export off today (`FileExportStartProcessor.java:65-98`) | Binds the request (`RequestBinder`), resolves the filename, calls `startExport`, and answers `respondAccepted` (`FileImportProcessor.java:266-289`): **202, `Location`, JSON `{transferId, statusUrl, fileUrl}` — for every caller.** No `Negotiation.prefersHtml` branch, unlike the import commit leg (`ImportCommitProcessor.java:124-128`, `:161-207`): an htmx confirm gets 202 and the running card, a plain form post gets 303 to `{path}/{transferId}`, where `ImportPages.renderCard` (`:48-56`) renders the same card inside the app's chrome. A browser posting a file-export form today lands on a JSON document. |
| 3 | The card (`tql/view/job-card.html`, `JobCards.java`) | Five states from the transfer's status (`:92-99`): running carries `hx-get` + `hx-trigger="load, every 2s"` (`:27`), backed off to `every 10s` past 5,000 rows (`:33-34`); a terminal card carries no trigger (`:48-50`); `data-hc-job`, `data-state`, `hx-target="this"`, `hx-swap="outerHTML"`; the done state of an **EXPORT** is the `Download` link to `statusUrl + "/file"` (`:60-63`); failed is the catalog's sentence for the recorded code, `tql.job.reason.<code>` (`:126-147`); the tombstone is `data-state="expired"`, 200, no trigger (`:77-88`). The card carries its own Cancel `<form>` (`job-card.html:34-41`). Pinned by `ImportPageIntegrationTest` (`:205-287`: done without trigger, tombstone, the page in chrome, cancel). |
| 4 | What the status face knows about a reclaimed export (`FileTransferService.java:221-223`, `:271-276`; `JdbcFileTransferService.java:777-780`, `:920-948`) | `expireTransfersOlderThan` sets `spool_uri` to null and keeps the row; the **console's** `TransferSummary` derives `expired` from "completed with no spool left"; the **status's** `TransferStatus` has no such component, so `JobCards.state` reads `COMPLETED` and renders **Done with a Download link that answers 410** (`TQL-LD-2868`, `docs/file-transfers.md:375-377`). The csv-import table promised "expired: an unknown *or swept* transfer id" (`:499`); the swept half is unbuilt on the card. |
| 5 | The grid page's regions (`tql/view/list.html`) | The search box sits **outside** the grid form (`:34-46`), `hx-get` on the page path with `hx-include` of the region's hidden inputs and no `hx-push-url`; the applied filters and `sort`/`dir` are hidden inputs **inside** the swapped region `#<id>-table` (`:106-116`); the status line, both result-cap surfaces and the pager are inside it too (`:117-174`); one `<form method="post">` wraps the region, the selection bar and the snapshot `keys` (`:72-82`), and its `_csrf` renders only when `actions:` or a snapshot is declared (`:73-75`); each bulk action is a submit button with its own `formaction` (`:99-101`). The filter dialog carries hidden `sort`/`dir`/search rendered at page render, outside the region (`:191-194`). |
| 6 | The list's state as a value (`ViewBinding.java`) | `state(params)` (`:1332`) = `chromeState` (`sort`, `dir`, `size`, the `search:` term, `:1507-1521`) + `filterState` (every declared filter with a value, `:1524-1536`); `returnBase` (`:1253-1264`) prepends `page` and is the `_return` every row link carries; chips, clear-all and presets all build from the same pieces through `href(pagePath, state)` (`:1540`). `params` is the route's **coerced declared inputs** (`:1552`). The count: `page.totalRows` on a counted offset page and on a snapshot (`:1017-1037`, `:1432`); mode A hedges it as `{max}+` (`:1009-1015`); mode B withholds it (`:986-992`). |
| 7 | How a POST route reads its question (`Request.java:90-95`, `RequestBinder.java:349-361`, `:231-246`, `:39-56`) | "The one merged parameter view: path, then query, then form — first value." A declared input sourced from the query string binds on a POST route exactly as on a GET; a form field of the same name does not listify against it (listification is a form's repeated field, `:238-243`). Reserved, never mass assignment: `_csrf`, `_idempotency`, `_return`, the lock pair, `keys`, `page`, `size`, `ids`. The gallery's list SQL binds its conditions as `params: { q: query.q, status: query.status }` (`examples/helpdesk-app/web/tickets/get.yml`) — **the query spelling, not `params.*`**. |
| 8 | The pagination clause (`docs/pagination.md:4-5`) | "The framework appends the dialect's pagination clause at execution time, so the authored 2-way SQL stays plain-tool runnable and carries no `LIMIT` of its own (`TQL-YAML-1018`)." A list's statement run by an export route is the whole answer by construction; `/*# order by {sort} */` binds `params.sortSql` from a `type: sort` input on either route. |
| 9 | Who may read a transfer (`TransferScope.java:10-52`, `docs/file-transfers.md:539-545`) | The `{transferId}` subtree is secured like the route that mounts it (`applyCommonGovernance` on status, cancel and file, `RouteCompiler.java:1893-1921`, `:1806-1812`); `TransferScope.own` narrows to the app, the route **and the tenant**, deliberately **not the subject**: "a transfer's readers are whoever the route admits, so a colleague under the same policy can fetch a link the exporter shared." `tql_file_transfer` records no subject (`V1__framework_operations.sql:51-66`, `V13`, `V15`). Pinned by `TransferRouteScopeIntegrationTest`. |
| 10 | How a view rule reaches its route (`ViewRules.java:136-197`) | `inputs = route.definition().input()`; an `actions:` entry must match a **POST** route by `urlPath` (`TQL-VIEW-3325`, `:158-175`); a preset param must be a declared input or `sort`/`dir`/`size` (`TQL-VIEW-3324`); a filter must be a declared input (`3323`). Codes `3301`–`3330` are taken; **`3331` is free.** `ErrorCodeUniquenessTest` requires an `ANTICIPATED` entry for a code raised by lint and runtime alike (`:106-118`, the `VIEW-3323` precedent). |
| 11 | How a render-time permission is judged (`WorkflowViewBinder.java:84-100`) | `PolicyEngine` looked up from the beans; a transition whose policy the engine refuses — or whose engine is absent — is not rendered (fail-safe false). The bulk action buttons carry no such check: a route the principal cannot call renders a button that answers 403. |
| 12 | Idempotency on the file recipes (`RouteCompiler.java:1749-1812`, `:882`, `:1986`, `:2059`, `:2250`; `docs/idempotency-key.md`) | `applyIdempotencyBegin/Complete` ride the json, transactional-command, template-page and MCP builders; **`buildFileExport` carries neither**, so a `file-export` route declaring `idempotency:` claims nothing. `_idempotency` is minted per HTML render and published to every view model; `form.html` renders it as a hidden field. The upstream async-job contract: "Kick-off POSTs compose with idempotency-key — a double-clicked Export should yield one job, and the replayed 202 points both clicks at the same card." |
| 13 | The upstream shapes | `recipes/async-job/contract.md`: a kick-off form, POST, `hx-target="#job"`, `innerHTML`, `hx-disabled-elt="this"`, a real `action`; the 202 carries the running card; expired is 200 and no trigger; Failed offers Retry as **a new kick-off**. The `data-grid-page` template (`apps/docs/…/templates/data-grid-page.mdx`): Export sits in the toolbar under "acts on the **data**", and "**Export carries the query.** The button says *Export 5,000 rows* and its href holds the same conditions and columns — a download means this question, not the rows on screen." The `result-cap` contract's mode A banner: "Narrow the filters to see the rest, or export the full set to CSV" — "exports run under their own, much larger, usually asynchronous limit." `hc.min.css` 0.4.2 ships `hc-empty__actions`. |
| 14 | The gallery | Eight declarative lists. `helpdesk-app` tickets declare `search: q`, `filters: [status, priority]`, two presets, sortable columns, a `type: sort` input (`web/tickets/`); `inventory-app` products declare `search: q` and `refreshOn: prices.imported` beside the reviewed price import (`web/products/`); `purchase-request-app` requests are the snapshot queue (`cap: 500`). Two tabular exports exist, both `query-export` CSV, neither reachable from a list: `users.export` (`user-admin-app`, whose users page is hand-written) and `api.shipments.export` (`procurement-app`, `bom: true`); the other `query-export` routes are the procurement demo's printable documents, linked from detail pages. **No gallery route declares `recipe: file-export`.** |
| 15 | The `after:` commit and the live list (`FileExportStartProcessor.java:90-96`, `docs/caching.md` S3) | An `ExportRequest` carries no topics and no tables: a `file-export` whose `after:` statement marks rows as extracted commits a write that neither `emit:` nor `invalidates:` can name. A products list with `refreshOn:` never learns its rows were marked. |
| 16 | The CSRF token on the two kick-off legs (`AuthStep.java:428-430`) | "The token comes from the `X-CSRF-Token` header (the `installCsrfHeader` htmx convention) or, for a no-JS plain form post, the hidden `_csrf` field." Both faces of one kick-off can carry the field, and neither leg needs the other's transport. |

## The mechanism

A list view names the routes that answer its question as a file. Each is an ordinary export
route the author already knows how to write — the same `sources.main` statement the list runs,
without the page window — and the grid page renders one control per entry beside the count,
carrying the list's current question as the route's query string. The route's recipe decides
what the control is: a `query-export` is a link, and the download is the answer; a `file-export`
is a form, and the answer is the running card, in a region of the page that survives the grid's
own swaps.

```
list.view.yml                      web/tickets/export/post.yml       the page
  exports:                           recipe: file-export                1–20 of 56 · [Export 56 rows]  ‹ Prev 1/3 Next ›
    - action: /tickets/export        input: q, status, priority,           │
                                            sort, dir                      │ POST /tickets/export?q=vpn&status=open&sort=-created_at
                                     sources.main.file: tickets.sql        ▼
                                     export: { format: csv,             ┌ #tickets-export-job ─────────────────────────────┐
                                               filename: tickets.csv }  │ [Running] 12,300 rows                 [Cancel]  │  ← polls itself,
                                                                        └──────────────────────────────────────────────────┘     stops when done
```

Nothing in the card, the status mount, the transfer service or the export pipeline changes
shape. What is new is the declaration, the control, the kick-off's answer to a browser, one
lint, and the card's knowledge that a reclaimed export is gone.

## The decisions

Each is recommended. The direction — design first, the async-job trigger fired from the list
surface — is the user's; the rest is the shape that direction takes.

### 1 — `exports:` on a list view names the routes; the recipe decides the shape

**Recommended.** A list view gains `exports:`, a list of entries in the `filters:` spelling — a
bare route path, or a mapping `{ action, label }`:

```yaml
# list.view.yml
exports:
  - /tickets/export                       # the route's path, as an actions: entry names its route
  - { action: /tickets/export.xlsx, label: Excel }
```

`action` is the vocabulary a view already uses for "the route this submits to" (`actions:`, a
form's `action:`). The target must be a **`query-export` GET route or a `file-export` POST
route** of the same application — anything else is refused (decision 6). The control's shape
follows the recipe, not a second key: a `query-export` renders a link and the click downloads
the file, the synchronous shape the upstream template draws and the shape a small list wants; a
`file-export` renders the kick-off form and the answer is the card. Choosing between them is
[file-transfers.md](file-transfers.md)'s own "Choosing a recipe" — "asynchronous extraction by
changing the recipe, not the column layout" — and this record adds nothing to that choice. It
is one key on the view rather than a synthesized route because the export pipeline's
declaration surface (`export:` with its format, template, columns, `splitBy`, `after:`,
`statusWhen`) is a route's, and copying it onto a view would fork the vocabulary the csv-import
design refused to fork.

`exports:` is a list-view key (`filters:`, `presets:` and `actions:` set the precedent at
`ViewSpec.java:317-328`), strict at every nesting level (`TQL-VIEW-3314`), and `label` renders
through the catalog first like an action's label. The default label is decision 3's.

### 2 — The question travels as the URL's query; the export route receives what the list bound

**Recommended.** The control's URL is the export route's path plus the list's **question**:
every declared input of the list route that the current request bound, as `ViewBinding.state`
already serializes it for the pager, the chips and `_return` — the search term, every applied
filter, `sort` and `dir` — minus the page window (`page`, `size`, `after`) and minus the
snapshot membership (`keys`). The same URL is the link's `href` on a `query-export` and the
button's `formaction` and `hx-post` on a `file-export`. Three reasons it is the URL and not
hidden fields:

- **One spelling on both altitudes.** A `query-export` link *is* a URL; giving the POST the same
  URL means the export route's `params:` mappings keep resolving whether the author wrote
  `query.status` (the gallery's spelling, row 7) or `params.status`. Hidden fields would have
  bound `params.status` and left `query.status` null — a shared statement would have silently
  answered *every* row, the silent-tolerance shape this codebase has swept twice.
- **It is never stale.** The control renders inside the swapped region (decision 3), so a
  search, sort or page swap re-renders it with the state it carries; and because the state is
  the URL, no second copy of the region's hidden inputs is needed and no `hx-include` can drift
  from the no-JS path.
- **Both legs ask the same question.** A native post and an htmx post send the same URL; the
  POST body carries the framework's two fields (decision 10) and nothing else, so the binder
  reads every condition from one source (row 7).

**The membership is not the question.** On a snapshot list the export re-runs the search, never
the frozen `keys`: the cap bounds what one operator works, the export is how they take the
whole set away (decision 8), and a work queue's membership is a paging device rather than a
condition. "Export what I have selected" is a different action (decision 11). Path parameters
are part of the question too: an export route under a path (`/customers/{id}/orders/export`)
declares the list route's parameters or a subset, and the control interpolates the current
values; the lint (decision 6) holds the two paths together.

### 3 — The control lives in the navigation strip, with the count it can vouch for, and in both cap surfaces

**Recommended.** The grid page's strip — the status line at the start, the pager at the end
(`list.html:136-174`) — gains the export control after the count: `1–20 of 56 · Export 56 rows
… ‹ Prev 1/3 Next ›`. The upstream template puts Export in a toolbar under "acts on the data";
this page has no toolbar row, its closest cousin (the search cluster) sits outside the swapped
region and would go stale on the first in-place search, and the number the label carries is the
number the status line shows — the two must re-render together or they will disagree. So the
control is chrome of the *answer*, beside its count, inside the region. Recorded as a deviation
from the template's composition, for the reason given.

The label is the list's count, hedged exactly as the status line hedges it:

| the list knows | the control says | key |
| --- | --- | --- |
| an exact total (counted offset page; a snapshot's membership, "as of search") | `Export 56 rows` | `tql.view.exportRows` |
| a truncated total (mode A, "500+ results") | `Export all matching rows` | `tql.view.exportAll` |
| no total (uncounted, keyset, an unpaged list) or the reject block (mode B) | `Export` | `tql.view.export` |
| an authored `label:` | the label, through the catalog | — |

The done card says the real number; the label is a promise about the question, not the file.
A list showing zero rows still renders the control — an empty export is a header and a file
(export-hygiene P5), the output shape being a property of the route rather than of today's
data, and a control that disappears on a stale count is worse than one that says "0 rows".

The two result-cap surfaces carry the control as the escape hatch their contract names.
Mode A's banner body gains the sentence when an export is declared — `tql.view.truncatedBodyExport`:
"More than {max} rows match. Narrow the search or filters to see the rest, or export the full
set." — and the strip below it renders `Export all matching rows`. Mode B renders no rows and
no pager, so the reject block's `hc-empty__actions` part (row 13) carries the control and its
body says why: `tql.view.overCapBodyExport`: "Narrow the search to at most {cap} rows, then work
the list — or export the full set." The banner and the block are inside the region already
(`list.html:117-130`), so the control they carry is current by the same rule.

**The `file-export` control is a button of a form that is not the grid form.** The grid form
wraps the region (`list.html:72`), and a form cannot nest, so the kick-off form is a small
sibling rendered before the grid form — `<form id="<id>-export" method="post">` holding
`_csrf` and `_idempotency` (decision 10) — and the button inside the strip belongs to it by
`form="<id>-export"`, with `formaction` carrying decision 2's URL. Natively the browser posts
the two fields to that URL; the grid form's `keys`, `ids`, `_return` and hidden conditions do
not travel, so nothing arrives twice and nothing reserved arrives at all. The htmx face on the
same button: `hx-post` the same URL, `hx-include="#<id>-export"`, `hx-params="_csrf,_idempotency"`
(the enclosing grid form's values are collected by default and filtered out here),
`hx-target="#<id>-export-job"`, `hx-swap="innerHTML"`, `hx-disabled-elt="this"` — the upstream
kick-off with the house's double-submit guard. The `query-export` control is a plain `<a>` with
the URL; the shell boosts nothing, so a download link needs no opt-out.

### 4 — The kick-off answers the recipe's three faces, through one shared answer

**Recommended.** `FileExportStartProcessor` stops answering every caller the JSON 202. After
`startExport` it asks `Negotiation.prefersHtml` (row 2): an htmx request (`HX-Request: true`)
answers **202 and the running card**; a plain form post (`Accept: text/html`) answers **303 to
`{path}/{transferId}`**, the transfer's own page in the app's chrome, which the status mount
already renders with the same card; every other caller keeps **`respondAccepted`** — 202,
`Location`, `{transferId, statusUrl, fileUrl}` — unchanged. The branch is lifted out of
`ImportCommitProcessor.respondBrowser` (`:161-207`) into one `TransferKickoff.respond(exchange,
transferId, …)` both processors call, so the two kick-offs cannot drift on what a browser is
told. The card the 202 carries is `JobCards.of` rendered through `ImportPages.render` with
`tql/view/job-card` — the model and the fragment the status poll will answer with two seconds
later, one markup source.

Two things the shared helper does not do. It does not answer the card to a native post: htmx
surfaces a redirect status to the XHR rather than to the tab, and a plain browser cannot poll,
so the two shapes are two legs on purpose (csv-import's recorded deviation, unchanged). And it
does not carry `_return` to the transfer page: that page is a real URL in the app's chrome, the
browser's own history returns to the list, and a status page that redirected back to a list
would lose the card the user came to watch.

### 5 — The card is the shipped card, in its own region; terminal is no trigger; a reclaimed export is expired

**Recommended.** The grid page gains one region, `<div id="<id>-export-job">`, rendered when
`exports:` names a `file-export`, placed **outside the grid form and outside the swapped
region** — between the applied-conditions chips and the grid form. Outside the grid form
because the card carries its own Cancel `<form>` (row 3); outside `#<id>-table` because a page,
sort or search swap must not tear down a running card. It is chrome that stays put for the
duration of a run, so it takes its place among the chrome regions above the fill chain, and it
renders nothing until a kick-off fills it. A second kick-off replaces the card — one region,
the latest job, the upstream shape; the earlier run continues and its status page stays
reachable by its URL.

Every state is the card's own, unchanged: the running card polls `{path}/{transferId}` on the
cadence `JobCards` writes, backs off past 5,000 rows, and offers Cancel; the done card of an
export is the `Download` link to `{path}/{transferId}/file`; the failed card names the
framework's sentence for the recorded code (`tql.job.reason.<code>`, export-hygiene P8) and
the code; cancelled says nothing was written; a terminal card carries no trigger and the
polling ends because there is nothing left to poll with; an unknown id is the 200 tombstone.
The list page adds no Retry to the failed card and no "start again" to the done one: the
kick-off is on screen, one line above, and it carries the current question — which is the
upstream's own rule that retry is a new kick-off.

**One card change: a reclaimed export is expired.** Row 4 measured the gap the csv-import table
promised away — after the retention sweep a completed export's card says Done and its link
answers 410. `TransferStatus` gains what `TransferSummary` already derives, `fileReclaimed`
(completed, direction EXPORT, `spool_uri` null), `JobCards.state` maps it to `expired`, and the
tombstone's body says the file is gone rather than the job. The JSON status face gains the same
boolean as `expired`, so an API poller learns it without a 410 round trip. A bookmarked status
page and a long-open tab therefore agree with the console.

**Without JavaScript** the kick-off is a form post, the answer is the transfer page, and every
state is reachable by refreshing it — the contract's manual-refresh degradation, already shipped
for imports. A snapshot list's pager is a whole-document POST, so turning a snapshot page while
an export runs loses the card (not the run); recorded, and the reason a job inbox is filed
below rather than dismissed.

### 6 — One judgement on both altitudes: `TQL-VIEW-3331`, the export route accepts the list's question

**Recommended.** A new rule in `ViewRules`, ERROR, raised by the linter and by the compiler
(`ViewBinding.of`, which already receives `postRouteByPath` and grows a GET twin), with the
`ANTICIPATED` entry `ErrorCodeUniquenessTest` requires. Its arms, each a way the control would
answer 400 or a different question:

1. **recipe** — the `action` path matches no route, or a route that is neither a `query-export`
   GET nor a `file-export` POST: "targets …, which is not a query-export or file-export route";
2. **input** — an input the list route declares (its search, filters, `sort`, `dir`, and any
   other declared input the kick-off carries) is not declared on the export route, or is
   declared with another `type`: the value the list bound would be refused as an unknown field
   or bind as something else;
3. **sort** — a `type: sort` input on the export route whose `columns:` allowlist lacks a
   column the list's allowlist admits: the list can hand over an order the export refuses;
4. **required** — an input the export route declares beyond the list's is `required`: the
   kick-off never sends it;
5. **path** — the export route's path declares a parameter the list route's path does not.

The recommended authoring shape, stated in the docs and taken by both dogfoods, is that the
export route declares the list route's `input:` block verbatim and names the same SQL file: the
question is then the same by construction, the row scoping in the statement (`principal.*`
binds and all) is the same, and only the page window differs (row 8). The lint does not judge
the SQL — an export that selects twenty columns where the list shows six is the ordinary case —
and it does not compare `security:` blocks (decision 7). A `label` is not judged: it is a label.

### 7 — The control renders for a principal the export route admits; the route decides; the transfer's readers stay the route's

**Recommended.** At render, `ViewBinding` judges each `exports:` entry the way `WorkflowViewBinder`
judges a transition (row 11): the export route's `security.policy` through `PolicyEngine.permits`
for the request's principal, fail-safe false when a policy is declared and no engine answers;
a route with `auth:` and no policy renders for any authenticated principal; a public route for
everyone. A control the principal cannot use is not rendered — the bulk actions' 403-on-click
is the shape this avoids. The judgement is a courtesy of the page: the route's own
`applyCommonGovernance` re-authorizes the GET or the POST, and every leg of the transfer
subtree is guarded by the same declaration ([file-transfers.md](file-transfers.md) "Security").
The export's `security:` block is not required to equal the list's and is not linted against
it: a download may legitimately need a stronger grant than a look, and a weaker one is the
author's visible choice in the file that declares it.

**The transfer's readers are the route's, not the exporter's — E0 stands.** Row 9 recorded the
choice: a colleague under the same policy can fetch a link the exporter shared. This action
does not narrow it to the subject, for three reasons. The rows in the file were extracted under
the exporter's principal and tenant, so "the file A exported" means A's scope at extraction
time, whoever opens it — that is what a shared export *is*. The id is an unguessable capability:
the only way to hold it is to have kicked the job off or been handed the link. And the
alternative refuses the ordinary use of a finished export in a back office — one operator
runs it, another picks it up — while protecting nothing the route's policy does not already
decide. Under tenancy the scope already includes the tenant. Recorded as the decision this
action was asked to make about permissions: **no change, on purpose.** The one reader outside
the route's policy is the operator with the console grant, whose transfers page lists every
transfer; that is the console's grant, as today.

### 8 — Two bounds: the list's cap bounds the screen, the export runs under the pipeline's own; the count is a label, not a denominator

**Recommended.** The result cap and the export's bound are different instruments and this
design keeps them apart.

- A **mode A** truncation (`materialize: { maxRows, onOverflow: warn }` on the list's source) is
  a bound on what one page fetch materializes; the export route runs its own statement through
  the streaming profile (`ExportRequest` carries the SQL file, not the list's `SqlStep`), so the
  list's bound never touches it. The export's own bound is the export pipeline's: a streaming
  format (`csv`, the Excel grid) is uncapped, a buffering format runs under `export.maxRows` /
  `onOverflow` (`TQL-LD-2850`, [export-pipeline.md](export-pipeline.md) decision 7), and a
  failed cap is the failed card's sentence (`tql.job.reason.TQL-LD-2850` exists). The banner
  offers the export *because* the bounds differ — the contract's "exports run under their own,
  much larger, usually asynchronous limit."
- A **snapshot `cap:`** bounds the membership one operator works; **mode B** renders no rows and
  offers the export because the export is the way to take the over-cap set away (decision 2:
  the export re-runs the question). A `query-export` target streams it; a `file-export` target
  runs it in the background. Neither reads `cap:`.
- **The count on the control is the list's count** (decision 3) and it is handed to nothing.
  The transfer's `expected_rows` — the denominator that turns the running card's "12,300 rows"
  into "12,300 of 30,000" with a determinate bar — stays the reviewed import's alone. Its
  migration says why (`V13__transfer_expected_rows.sql`): "the review already parsed the whole
  file, so the count is a fact rather than an estimate … the card counts up without a total —
  which is honest, where a guessed denominator would not be." A list's total is a count taken
  before the extraction, of rows that move; a bar that ends at 57 of 56 is the lie that
  sentence refuses. Declined, recorded here so it is not re-proposed as an afterthought.

### 9 — `statusWhen:` stays the synchronous recipe's; on the asynchronous action an empty set is a file and a failure is the coded card

**Recommended.** `export.statusWhen` answers a status instead of a document on a `query-export`,
and the linter refuses it on a `file-export` because "a `file-export` answers 202 before its
rows are read" (`TQL-YAML-1041`, [file-transfers.md](file-transfers.md)). Nothing here moves
that line. A `query-export` target keeps its arms: the link navigates, and a matched arm
answers its declared status as the route's error envelope — the author declared it, and a link
cannot render it inline. A `file-export` target has no pre-flight: zero rows complete as a
header-only file (export-hygiene P5) and the done card says "0 rows"; a statement that fails,
a document that cannot be written, a cap that is exceeded — each is the failed card carrying
`tql.job.reason.<code>`. The asynchronous analogue of a declared status is a terminal card
state, and the card already has them all.

### 10 — The kick-off composes with the idempotency key; `file-export` gains the pair it lacked

**Recommended.** The kick-off form (decision 3) carries `_idempotency` beside `_csrf`, as
`tql/view/form.html` does for every form the framework renders, and `buildFileExport` gains
`applyIdempotencyBegin` before the binder and `applyIdempotencyComplete` after the start
processor (row 12: the one browser-facing builder without them). A `file-export` route that
declares `idempotency:` then answers a replayed key with the stored 202 and the same card —
"the replayed 202 points both clicks at the same card" — and the shell's network-retry Retry,
which re-issues the POST, yields one transfer instead of two. A route that declares no
`idempotency:` ignores the field, as every form's does; `hx-disabled-elt="this"` remains the
first guard on the htmx path either way. `Begin` reads the header first, then the field
(idempotency-key decision 5), so a scripted caller's `Idempotency-Key` header works on the same
route unchanged.

### 11 — What this action refuses, each with its trigger

- **Export the selection.** An `actions:` entry whose route is a `file-export` — the checked
  rows' tokens as `ids`, the card in the same region — is the natural sibling and is not this
  action: the selection posts through the grid form, whose fields decision 3 deliberately keeps
  out of the kick-off. *Trigger: the first gallery list whose bulk verb is "export these".* Once
  decision 4 lands, a native bulk post to a `file-export` already lands on the transfer page,
  so the sibling is mostly the decode and the region.
- **A column picker.** The file's columns are the export route's `export.columns:`; the ledger
  names an export column picker as `data-hc-select-all`'s trigger and this record leaves it
  there.
- **A format menu.** One entry per format (`label: Excel`) is the declared answer; a picker
  over one route would need the route to accept a format it did not declare.
- **A job inbox** ("my exports"). The upstream note — the recipe applied per row over a
  `data-region` — needs a transfer that knows its subject, and `tql_file_transfer` records none
  (row 9). *Trigger: the first gallery flow in which an export outlives the page that started
  it*; the snapshot pager's whole-document POST (decision 5) is where that will first be felt.
- **Server push.** The card polls; the completion signal for a *write* rides `emit:`/`refreshOn:`
  and an export writes nothing a list watches — except an `after:` statement, filed below.
- **Mail delivery of the finished file.** A job's `push:` and the notification channels exist
  for that; a page's export is a download.

### 12 — Docs, ledger, CHANGELOG

**Recommended.** [declarative-views.md](declarative-views.md) "List views" gains "Exporting the
filtered set: `exports:`" after the bulk-actions section — the declaration, the two shapes, the
count label, the rule that the export route declares the list's inputs and shares its statement,
the lint. [hypermedia-ui.md](hypermedia-ui.md) gains "Exporting a list" beside "Uploads that ask
before they write": the kick-off form, the card region, the three answers. [file-transfers.md](file-transfers.md)
"Choosing a recipe" gains one sentence: a list's `exports:` renders either recipe, as a link or
as the card. [hc-recipe-alignment.md](hc-recipe-alignment.md): the async-job row reads
"Adopted (#1127–#1133, and the list surface's export — [list-export.md](list-export.md))" and
the section's trigger paragraph is replaced by a "Designed:" block naming this record; the
status block gains the bullet. [list-surface.md](list-surface.md)'s "Where this design diverges"
bullet "Export already has a pipeline and can occupy the toolbar via slots" gains a pointer to
this record. `CHANGELOG.md` per slice, under Unreleased: S1 `### Added` (`exports:` on a list
view; a native form post to a `file-export` lands on the transfer page) and `### Changed`
(markup contract: the grid page's navigation strip and the two cap surfaces render the export
control); S2 `### Added` (the kick-off answers the job card; `idempotency:` on a `file-export`
takes effect; a reclaimed export's card and status say expired).

## What this breaks

- **`tql/view/list.html` L2 overrides render no export.** An application that ships its own
  `templates/tql/view/list.html` (the customization ladder's level 2) keeps rendering its copy;
  `exports:` on its views lints clean and shows nothing until the override adopts the strip,
  the form and the region. The override lint checks the fragment signature, not the contents
  (csv-import decision 4 recorded the same limit); the CHANGELOG says it loudly, as that record
  did, and the markup-contract entry names the three additions.
- **A `file-export` route answers a browser's form post with 303, not JSON.** A client that
  posts a form with `Accept: text/html` and parsed the JSON envelope gets a redirect after S1.
  No such client is in the tree or the docs — the recipe's documented callers are scripts and
  `hx-*` forms — and the JSON contract is unchanged for every caller that names it or names
  nothing (`Negotiation.prefersHtml`, row 2).
- **A reclaimed export's status says `expired: true`** and its card says expired where it said
  done with a dead link (decision 5). Additive on the JSON; a change on the card; the 410 on
  the file leg stands for a caller that ignores both.
- **`idempotency:` on a `file-export` route does something.** It compiled and claimed nothing
  before; a route that declared it will now answer a replayed key with the stored response.
  No gallery route declares it.
- The mode A and mode B copy changes only when `exports:` is declared (new keys); the existing
  keys and their tests are untouched.

## Filed, not fixed

- **A `file-export`'s `after:` commit announces nothing** (row 15). A products list with
  `refreshOn:` never learns its rows were marked extracted, and a held source that read them
  is not dropped. The fix is `announcing`/`invalidating` on `ExportRequest` for the `after:`
  leg — the caching record's S3 shape, applied where the export commits. *Trigger: the first
  `after:` export beside a live list or a held source.* Not this record's: no `after:` export
  exists in the gallery, and the export this action kicks off marks nothing.
- **The in-place search does not push the URL** (`list.html:34-41` has no `hx-push-url`), so the
  address bar, the filter dialog's hidden search (`:193-194`) and a bookmark fall behind a typed
  search until the next navigation. The export control is inside the region and is not affected;
  the chips and the dialog are. Filed to the list surface as a measured quirk.
- **A `query-export` link's `statusWhen` arm answers an error page** to a click from the grid
  (decision 9). A link cannot do otherwise; a route that wants the reject inline is a
  `file-export`, whose card says it. Recorded so it is not reported as a defect.
- **A second kick-off hides the first card** (decision 5). The run continues and its status
  page is reachable by URL, but the page shows one card. The job inbox (decision 11) is the
  answer, when triggered.
- **Two lists exporting through one route** is fine and unjudged: the lint judges each view
  against the route; a route serving two lists must declare the union of their inputs, which
  the input arm says per view.

## Deliberately not in this design

- Any change to the card's states, cadence or markup beyond the reclaimed-export mapping —
  the card is the csv-import campaign's and the contract's.
- A scheduling or recurrence surface: that is a job with an export step ([jobs.md](jobs.md)).
- A per-user "saved export" or a remembered format — the per-user store that `datagrid-prefs`
  and saved views wait on.
- Composite keys, `splitBy` or `groupBy` on the action: they are the export route's own
  declarations and work unchanged; nothing here reads them.
- The synchronous route's streaming, the transfer's retention, the temp store — unchanged and
  cross-referenced only.

## The slices

### S1 — the declaration, the control, the link, the redirect (M)

Code: `ViewSpec.exports` (record component, `parseExports` in the `parseFilters` shape, the
list-view refusal), both schema copies (`tesseraql-yaml/src/main/resources/schema/tesseraql-view-v1.schema.json`
and `examples/scaffold-demo-app/.vscode/`, byte-identical), `ManifestCoverageTest.listView`;
`ViewRules` `TQL-VIEW-3331` with its five arms and the compiler twin in `ViewBinding.of`
(a `routeByPath` beside `postRouteByPath`); the `ANTICIPATED` entry; the strip control and the
two cap-surface placements in `list.html`, the kick-off form with `_csrf` (and `_idempotency`,
inert until S2), the render-time policy judgement, the count label and its six new message
keys in `en.yml`/`ja.yml` (`export`, `exportRows`, `exportAll`, `truncatedBodyExport`,
`overCapBodyExport`, and the reject block's action label if it differs); `ViewEjector` freezes
the same control; `FileExportStartProcessor` answers a native form post with the 303 (the
`respondBrowser` non-htmx arm, lifted into `TransferKickoff` now so S2 adds only the card arm);
the dogfoods: `inventory-app` gains `web/products/export/get.yml` (`query-export`, `products.sql`,
the list's `input:`, `inv.read`) and its list declares `exports: [/products/export]`;
`helpdesk-app` gains `web/tickets/export/post.yml` (`file-export`, `tickets.sql`, the list's
`input:` block including the `type: sort` input, `help.read`, `filename: tickets.csv`) and its
list declares `exports: [/tickets/export]`. Docs as decision 12 except the hypermedia-ui page.

Guards, each proven red on a built variant before the PR opens:

| Guard | Asserts | Variant that reddens exactly it |
| --- | --- | --- |
| yaml `ViewSpecTest` (+rows) | `exports:` parses a bare path and a `{action, label}` mapping in order; an entry without `action` is refused; an unknown entry key is `3314`; `exports:` on a form or detail view is refused as a list-view key | `parse-lenient` (unknown entry key ignored), `parse-anyview` (refusal dropped) |
| yaml `ViewRulesTest` / `AppLinterTest` (+rows) | `3331` on each arm: a path matching no route; a `query-html` route; a `file-export` lacking `status`; `status` typed `integer` on the export; a sort allowlist missing `priority`; a `required` extra input; a path parameter the list lacks. Clean: both dogfood pairs | `lint-noarm-<n>` per arm (the arm deleted) |
| compiler `JudgedOnceCompileTest` (+1) | a view whose export route lacks the list's `q` fails the build with `3331` and the linter's sentence | `compiler-nojudge` |
| compiler `HtmlResponseRendererViewTest` (+rows) | the strip renders the `query-export` link with `?q=vpn&status=open&sort=-created_at` and none of `page`/`size`/`keys`; the `file-export` button carries `form`, `formaction`, `hx-post` with the same URL, `hx-params="_csrf,_idempotency"`, `hx-target="#<id>-export-job"`; the kick-off form sits outside the grid form; `Export 56 rows` on a counted page, `Export all matching rows` under mode A, `Export` on keyset; mode B renders the control inside `hc-empty__actions` and the `…Export` body; no control when `exports:` is absent; no control for a principal the route's policy refuses | `label-static` (count ignored), `state-page` (page carried), `capB-noaction`, `policy-unjudged` |
| yaml `ViewEjectorTest` (+1) | the ejected list carries the strip control with the same attributes | `eject-drops` |
| runtime `HcMarkupContractTest` | every literal class and data attribute the new markup uses exists in the pinned kit (`hc-empty__actions` included) | by construction |
| runtime `ListExportIntegrationTest` (NEW, the helpdesk shape over Testcontainers) | with 45 tickets and `?status=open&q=vpn&sort=-created_at` (30 hits, page size 20): the rendered control carries the question; the `query-export` dogfood's link downloads 30 rows in that order, not 20; a native `POST` of the button's URL with `_csrf` answers 303 to `/tickets/export/<id>`; that transfer completes and its file holds the same 30 rows; the JSON 202 is unchanged for `Accept: application/json`; a principal without `help.read` sees no control and the POST answers 403 | `state-page`, `redirect-json` (303 arm removed), `policy-unjudged` |
| runtime `FileTransferIntegrationTest` (+1) | a form post with `Accept: text/html` to a `file-export` route answers 303 and `Location: <statusUrl>`; `Accept: */*` keeps 202 JSON | `redirect-json` |
| docs-reference `ErrorCodeUniquenessTest`, reference regeneration | `3331` held once in yaml, raised by the compiler through the same constant, listed `ANTICIPATED` | by construction |
| test-core `ManifestCoverageTest`, dogfood `ScaffoldDogfoodIntegrationTest` | the record component is constructed; both schema copies equal | by construction |

### S2 — the card on the page (M)

Code: `TransferKickoff` gains the htmx arm — 202 and `JobCards.of` rendered as `tql/view/job-card`
— and `ImportCommitProcessor.respondBrowser` delegates to it; `FileExportStartProcessor` calls
it; the `#<id>-export-job` region in `list.html`; `applyIdempotencyBegin`/`Complete` on
`buildFileExport`; `TransferStatus.fileReclaimed` (derived in `JdbcFileTransferService.status`
as `recent` derives `expired`), `JobCards.state` → `expired`, the tombstone body key
`tql.job.reclaimedBody` ("The file is no longer available."), `expired` on the status JSON;
`HypermediaComponentsManifestTest` unchanged (async-job is pinned since #1133);
[hypermedia-ui.md](hypermedia-ui.md) "Exporting a list"; the ledger row and its "Designed:"
block; the list-surface pointer; CHANGELOG.

| Guard | Asserts | Variant that reddens exactly it |
| --- | --- | --- |
| runtime `ListExportIntegrationTest` (+rows) | an `HX-Request` POST of the button's URL answers **202** with `data-hc-job`, no `data-state`, `hx-trigger="load, every 2s"`, `hx-get` naming the status URL; polling the status until the trigger is gone answers `data-state="done"` with the `Download` link; the link's bytes are the 30 rows; an `HX-Request` POST while a run is open answers a second running card whose id differs (the region swap is the page's, not asserted here); a pager swap's response does not contain the region (it is outside `#<id>-table`) | `kickoff-json` (htmx arm removed), `region-inside` (region moved into the table region) |
| runtime `ListExportIntegrationTest` (+1) | after `expireTransfersOlderThan(now)` the card of the completed export answers 200 with `data-state="expired"`, no trigger, no `Download`; the JSON status carries `expired: true`; the file leg still answers 410 | `reclaimed-done` (the mapping removed) |
| runtime `ImportPageIntegrationTest` | unchanged rows stay green with `respondBrowser` delegating — the import's 202 card and 303 are the helper's | `helper-drift` (the import leg's status code changed) |
| runtime `IdempotencyFormIntegrationTest` (+rows) or `ListExportIntegrationTest` | two POSTs with one `_idempotency` to a `file-export` declaring `idempotency:` create **one** transfer and answer the same card body; without `idempotency:` two transfers; a JSON caller's `Idempotency-Key` header replays the 202 envelope | `pair-missing` (Begin/Complete not applied), `pair-noreplay` |
| compiler `HtmlResponseRendererViewTest` (+1) | the region renders exactly when an entry is a `file-export`, empty, outside the grid form, before it | `region-always` |
| docs guards | `sync-content.mjs` (the ledger and pointer edits are internal docs; hypermedia-ui.md is published — no dead end, no internal vocabulary), `lint-prose.mjs` | by construction |

S1 before S2: S2's htmx arm replaces S1's 303 for the htmx caller only, and S1's native leg is
never changed — the button is plainer, never broken, between the two (the csv-import slices 4
and 5 argument).

## Docs and CHANGELOG

`CHANGELOG.md`, Unreleased:

- **S1 `### Added`** — **A list view exports its filtered set.** `exports:` on a list view names
  `query-export` or `file-export` routes; the grid page renders one control per entry beside the
  count — a link that downloads, or a form that starts the transfer — carrying the list's
  current search, filters and sort as the route's query. The export route declares the list's
  inputs and may share its statement; a route that would answer a different question is
  `TQL-VIEW-3331` at lint and at build. The truncation banner and the over-cap reject block offer
  the export as the way to the full set. A native form post to a `file-export` route lands on
  the transfer's page.
- **S1 `### Changed`** — **Markup contract.** `tql/view/list.html`'s navigation strip and both
  result-cap surfaces render the export control when `exports:` is declared; a level-2 override
  of the pattern renders it only once it adopts the strip, the kick-off form and the region.
- **S2 `### Added`** — **The export kick-off answers the job card.** An htmx kick-off from the
  grid page answers 202 and the self-polling card in the page's own region; the card stops by
  carrying no trigger, and a reclaimed export's card and status say expired. `idempotency:` on
  a `file-export` route takes effect: a replayed key answers the same card.

## Error codes

| Code | Domain | Raised | Meaning |
| --- | --- | --- | --- |
| `TQL-VIEW-3331` | VIEW | lint (`ViewRules`), build (`ViewBinding.of`) — `ANTICIPATED` | an `exports:` entry targets a route that is not a `query-export` GET or `file-export` POST, or one that would refuse the list's question: a list input it does not declare or declares with another type, a sort allowlist narrower than the list's, a required input the kick-off never sends, a path parameter the list route lacks |
