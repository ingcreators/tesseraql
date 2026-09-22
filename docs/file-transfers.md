# File import and export

Three route recipes move tabular files between HTTP and the database. `query-export` streams a
generated file back as the response — a plain download link. `file-export` and `file-import` run
asynchronously: the request starts a transfer and returns immediately, and the route owns a small
URL subtree for tracking it. All three share the same `export:`/`import:` vocabulary — formats,
column mappings, format patterns, locale and time zone — so a synchronous download can become an
asynchronous extraction by changing the recipe, not the column layout.

## Choosing a recipe

- **`query-export`** — synchronous. `GET` the route, get the file. The extraction is the route's
  `sources.main`, and rows stream from a database cursor through the codec into the response, so
  even large results never buffer in memory. Use it for downloads a user clicks. It cannot run
  follow-up statements.
- **`file-export`** — asynchronous. The request returns `202 Accepted` with a transfer id; the
  extraction runs in the background and the finished file is fetched later. Use it for large or
  slow extractions, and whenever you need an `after:` follow-up statement (for example, marking
  rows as extracted).
- **`file-import`** — asynchronous. The uploaded file is the request body; parsing and the
  per-row SQL run in the background while the client polls for the outcome, including per-row
  rejections.

A declarative list names either export recipe under `exports:`
([declarative views](declarative-views.md)): the grid page renders a download link for a
`query-export` and a kick-off button for a `file-export`, each carrying the list's current
search, filters and sort as the route's query. A browser's plain form post to a `file-export`
route lands on the transfer's own page, where the job card below renders; scripted callers
keep the JSON 202.

An uploaded import rides the runtime's request-body bound,
`tesseraql.http.maxBodyBytes` (default 10 MB; see deployment.md) — a feed larger than that
needs the bound raised, and the refusal is a 413 naming the key rather than a mystery.

`GET {path}/{transferId}` reports a transfer's state, and answers two ways: JSON for an API
caller, and a self-polling job card for a browser. The card carries its own polling attributes,
writes the cadence the server chose, and a terminal card carries no trigger at all — which is
how the polling stops. An id this runtime does not know is a `404` for the API and a `200`
tombstone for the card, because a poller that receives an error keeps polling an error.
`POST {path}/{transferId}/cancel` asks a running transfer to stop; the request is a flag the
import's row loop and the export's row source read between rows, so the stop lands at a row
boundary and leaves nothing written. An import is one transaction, and a stop before the commit
takes every applied row with it; an export rolls its extraction back, discards the partial file,
and records `STOPPED` with the rows it had reached.

Every transfer is also tracked as a batch execution, so imports and exports show up app-scoped
in the [operations console](ops-console.md). Being an execution, a running transfer writes the
same heartbeat a run does, and is read against the same
`tesseraql.batch.heartbeat.livenessWindow` ([jobs](jobs.md#who-owns-a-run)) — so a long import is
judged by whether it is still reporting, not by how long it has taken. A transfer whose node is
lost stops reporting, and the reaper finishes it with the abandoned-run reason rather than leaving
it shown as in progress forever. That sweep is per application, because a transfer started by a
route has no job id to be swept under. Imports can alternatively be driven by polling a local or
SFTP/FTPS directory instead of an HTTP upload — see [connectors.md](connectors.md).

## Synchronous download: query-export

A complete route (from the bundled user-admin example):

```yaml
version: tesseraql/v1
id: users.export
kind: route
recipe: query-export

security:
  auth: bearer
  policy: users.read

sources:
  main:
    sql:
      file: export.sql

export:
  format: csv
  filename: users.csv
```

`export.sql` is an ordinary query file colocated with the route; request parameters bind into it
like any other query route. The response carries the file with a `Content-Disposition` download
filename. A filename outside US-ASCII is sent in RFC 6266's `filename*` form beside an ASCII
`filename` fallback, so browsers save it under its own name and a client that reads only
`filename` gets an ASCII one. The whole `export:` block is optional — without it you get CSV,
every query column, column names as headers, and `<route id>.csv` as the filename.

The filename is a template over the request: `orders-{params.month}.csv` downloads as
`orders-2026-09.csv` for `?month=2026-09`, `order-{path.id}.pdf` names the order a detail
route prints. A placeholder is a dotted path over the request's roots: `params`, `query`,
`path`, `body`, `tenant`, `request`, `flags`, `preference`, and `principal` on an authenticated
route. Each value is folded to a filename component before it names the file (`2026/09`
becomes `2026_09`, an absent value `_`), so a request can name the download and never steer
where it is saved. A placeholder the request cannot resolve — an undeclared input, a path
parameter the URL does not carry, a spelling outside the grammar — is refused at lint and at
boot (`TQL-YAML-1076`). A `file-export` fixes the name when the transfer starts, so its status,
the `HEAD` and the download all report the same one.

Every recipe reads the same way: `export:` says how rows are written and never what to read, so
the extraction is a source like any other. An `export.after` block on `query-export` is
refused at lint and at boot (`TQL-YAML-1041`) — follow-up statements need `file-export`.

## The export: block

```yaml
export:
  format: excel               # csv (built in) | excel | pdf (optional modules)
  filename: orders.xlsx       # default: <route id> + the format's extension
  locale: de-DE               # csv/pdf only (a workbook never reads it); or a request source
  timezone: Asia/Tokyo
  columns:
    - name                    # simple form: column name is also the header
    - { name: held_on, label: Held on, type: date, format: yyyy/MM/dd }
    - { name: fee, type: number, format: "#,##0.00" }
  template: orders.xlsx       # workbook/print template colocated with the route
  sheet: Orders               # workbook formats: the sheet to write
  startCell: B5               # workbook placement mode: where data rows start
  maxRows: 5000               # formats that hold every row: the ceiling (see below)
  groupBy: department         # a workbook report template reads the rows as groups (see below)
  splitBy: customer_id        # one document per value, delivered as one ZIP named for the stem (see below)
  statusWhen:                 # query-export only: the first truthy arm answers its status, not a document
    - when: header.rowCount == 0
      status: 404
  after:                      # file-export only: the follow-up statement
    timing: extract           # extract (default) | download
    sql:
      file: mark-extracted.sql
```

The rows come from `sources:` beside it, never from inside `export:` — the extraction on every
recipe, and the other data a template composes around it, are sources with names:

```yaml
sources:
  main:
    sql:
      file: select-orders.sql   # the extraction
  header:
    sql:
      file: select-order.sql    # the template reads header.first.customer
```

- `format:` is required on a job step and may be omitted on a route, where `csv` is the default;
  a blank `format:` is refused everywhere, at lint and at boot, and so is a format no codec in
  the application's set serves — lint warns (`TQL-YAML-1408`), boot refuses (`TQL-LD-2801`)
  naming the route or the step, on every recipe alike. A `filename:` that carries
  `{key}` without `splitBy:` is refused too (the placeholder would be delivered literally), as is
  a `{dotted.path}` placeholder the request cannot resolve (`TQL-YAML-1076`), and a
  filename whose extension is not the format's draws a lint warning (`TQL-YAML-1045`) — the file
  is served and recorded as its format whatever it is called.
- `columns:` selects and orders the exported columns; omit it to export every query column with
  its name as the header. `label:` sets the label in the file (it may be localized text). An
  export with no rows still carries its header row — the declared columns, or the query's column
  names — so a consumer always sees the table's shape; a consumer asking "were there rows?" reads
  `rowCount` from the transfer status. An export that enriches its rows (`enrich:`) declares
  `columns:` if it wants a stable header on the empty day, because enrichment adds keys the
  query's metadata cannot know.
- `type:` (`date` / `datetime` / `number`) with `format:` renders a typed or formatted column
  through a date or decimal pattern on `csv` and `pdf`. On a workbook the string is the cell's
  own number format, in Excel's vocabulary (`d-mmm-yy`, `0.00E+00`), and no Java parser ever
  sees it. A column with neither is written as one text per kind on `csv` and `pdf`: a wall
  clock as stored (`2026-01-15 22:30:00.123456`), an instant in the export's zone, a date as
  `2026-01-15`, a time as `22:30:00`, a time with zone as `22:30:00+09:00`. The Excel grid and
  placement modes type every temporal cell; on the grid a `type: date` column takes the date
  cell format (`yyyy-mm-dd`) by default and a `datetime` one `yyyy-mm-dd hh:mm`. A jxls
  report (`template:` without `startCell:`) hands the template the raw values and reads none
  of these keys — except that a time of day reaches it as the day fraction the grid writes,
  so a report's time cells never carry the day the report ran. A binary column (`bytea`,
  `BLOB`) is written as Base64 on every text surface, the same text a JSON body carries.
- `domain:` says `type:` and `format:` once: `{ name: held_on, domain: held_date }` takes them
  from the app-level field domain ([field-domains.md](field-domains.md)) — the same domain the
  request's `input:` binds and a `result:` entry reads back — and the column's own `type:` or
  `format:` wins over the domain's. `label:` and `column:` stay the file's. A domain's `locale:`
  is not applied to a column (a file has one locale, the block's), nor are its constraint keys.
  A job's export step and poll import resolve it the same way.
- A column is read in the kind the database declares for it, and the server's own time zone
  never enters. A zoneless `timestamp` or `datetime` is a wall clock: `timezone:` leaves it
  alone, typed or not. A `timestamptz`, `datetimeoffset` or `TIMESTAMP WITH TIME ZONE` is an
  instant: `timezone:` presents it in that zone (the platform's when none is declared). A
  time-of-day column has no date to shift and keeps its wall clock; a time with zone keeps its
  offset as text and its wall clock in a workbook cell. MySQL's `TIME` is a signed duration
  that may exceed a day: a value outside `00:00:00`–`23:59:59` is refused by Connector/J when
  read (the export fails with the driver's text) and wrapped modulo a day by MariaDB's driver —
  neither is a time of day, so select such a column as text (`time_format`) instead.
- `locale:` and `timezone:` drive those patterns, and reach only a typed or formatted column —
  and, on a `pdf` export with a `template:`, `locale:` also sets the locale the template renders
  in (its `#numbers` and `#dates` utilities, `${#locale}`, and `#{…}` message expressions). A
  template whose export declares no locale renders in English, as every locale-less template does.
  The linter warns when a `csv` or `pdf` export declares them over a column list with none; it
  cannot see a column the query derives. Each key stands on its own. A key is a literal such
  as `ja-JP` or `Asia/Tokyo`, or on a route a request source: `principal.claim.<name>`,
  `query.tz` naming a declared `input:`, `body.tz` (a declared input, unless
  `inputPolicy.unknownFields: ignore` admits any field), or `request.locale` (the negotiated
  request locale). A principal source names the identity provider's claim carrying the zone or
  the language tag, and nothing else: `principal.subject` or a forgotten `claim.` is refused at
  lint and boot. An input's `default:` is judged as the route's own literal is, because it is
  what the source names on every request that omits the input. Each key falls back on its own:
  the route's literal, else the request source
  when it resolves to a value, else the app configuration keys `tesseraql.files.locale` and
  `tesseraql.files.timezone` (literals, never source expressions), else the platform default. A
  job step's declaration is a literal and falls back to the same keys. A request-sourced value
  the server cannot use — `?tz=Tokyo`, `?loc=ja_JP` — is refused before the extraction runs, as
  a `TQL-FIELD-2001` field error naming the input with code `timezone` or `locale`; a
  file-export refused this way starts no transfer. A sign-in claim is held to the same rule and
  named by its expression (`principal.claim.zoneinfo`); a locale claim spelled `en_US` is read
  as `en-US`. An offset in a URL must be percent-encoded (`?tz=%2B09:00`), because `+` is a
  space in a query string. `locale:` drives nothing in a workbook — a cell carries a value and a
  cell format, and the reader's own locale renders them — so the linter refuses it on
  `format: excel`.
- Every literal is judged where it is written, and only where the format reads it. A zone the
  JDK does not know, a language tag it cannot format, a `csv`/`pdf` pattern its parser refuses,
  a malformed or out-of-workbook cell reference, or a mixed-case `format:` is a lint error and
  a boot refusal with the same code (`TQL-YAML-1063`). The message names the app, the route or
  job step, the key and the value. A key the format never reads — `bom:`, `sheet:`,
  `startCell:` or `template:` on the wrong format, `locale:` on a workbook, a `type:` the export
  does not render — is a lint error (`TQL-YAML-1005`) and a boot warning: the runtime serves
  without it. `tesseraql lint`, the boot and `tesseraql job run` share the one check.
- `bom: true` opens a `csv` export with the UTF-8 byte-order mark (`EF BB BF`), so a spreadsheet
  that sniffs the mark decodes the file as UTF-8 instead of its system code page. It is off by
  default, because a mark is a declaration a reader must expect: PostgreSQL `COPY … HEADER MATCH`
  and Python's `csv` module take it as part of the first header cell. It is never derived from
  `locale:`; a split export marks every file in the bundle, and an export with no rows still
  carries it, before its header. The linter refuses it on `excel` and `pdf` (`TQL-YAML-1005`),
  which are not text streams.
- `statusWhen:` answers a status instead of a document on a `query-export`: the first arm
  whose condition is truthy decides it. The arms are judged over the route's `sources:`
  (`header.rowCount == 0`) before the extraction opens, so a template never renders a header
  that has no row, and again with `main.rowCount` once the rows are written, when a matched
  arm discards the document it wrote. The answer is the error envelope with `TQL-LD-2863` and
  the declared status, the condition in its details. A `file-export` answers 202 before its
  rows are read and a job step answers no request, so both refuse the key (`TQL-YAML-1041`);
  the JSON and HTML renderers carry the same block under `response:`
  ([response shaping](response-shaping.md#conditional-statuses-statuswhen)).
- Excel output has three template modes: no `template:` renders a plain grid; a template plus
  `startCell:` is placement mode — the template carries layout and styles while the YAML says
  where each column lands (`- { name: qty, column: D }`); a jx:-annotated template without
  `startCell:` is a full jxls report. PDF output uses a colocated XHTML print template instead —
  see [printable-documents.md](printable-documents.md).
- A template path that does not exist is refused by the linter and at boot (`TQL-YAML-1006`)
  on the formats that read one — a workbook, a print template, a module format — instead of
  failing the first request; on `csv` the key is inert and warned about. `startCell:` without a
  template is refused: the mode a declaration selects should be the mode it names.

## What a template can see

An export's template reads the extraction's `rows`, and whatever else the route declares beside it:

```yaml
export:
  format: pdf
  template: order.html

sources:
  main:
    sql:
      file: select-orders.sql
  header:
    sql:
      file: select-order.sql    # the template reads header.first.customer
  rates:
    http:
      url: https://rates.example/today
```

An export's template sees the same shapes a route's template sees: every source under its own
name — the extraction under `main` — all carrying `rows`, `rowCount` and `first`.

```html
<h1 th:text="${header.first.customer}">Customer</h1>
<tr th:each="row : ${main.rows}"><td th:text="${row.item}">item</td></tr>
```

- **`sources:`** run on the extraction's own connection, inside its transaction and before it, so
  a document reads exactly the state its rows came from. This is how a header-and-lines document
  stops denormalizing its header onto every line.
- **Results are read in sequence**, so `rows[0]` does not resolve — a single-row query is read
  through `first`. A template that wants the third row wants a query that returns it.
- **`http:` sources** are declared on the route and reach the template the same way. On an export
  they run *before* the extraction, and on an asynchronous export they are called when the export
  is requested rather than when the worker gets to it: the data is as of submission, which is the
  rule bound parameters already follow, and no network call happens while the extraction holds a
  connection and a cursor. `onError: empty` is refused on an export — a document that is archived
  and mailed should not look complete with a section missing.
- Declaring either beside a format with no template is a warning (`TQL-LD-5312`): CSV and the
  Excel grid write rows and nothing else, so the source would run to be discarded.

## Large results

Which formats stream, and which hold every row before they write:

| Format | Streams | Capped |
| --- | --- | --- |
| `csv` | yes | no |
| `excel`, plain grid | yes | no |
| `excel`, jxls report | rows yes, workbook no | yes |
| `excel`, placement | no | yes |
| `pdf` | no | yes |

A format that holds its rows runs under `maxRows:`, defaulting to
`tesseraql.resultMaterialization.maxRows`; passing it fails with `TQL-LD-2850`, and
`onOverflow: warn` truncates instead. It counts the named queries too — a ceiling that bounds the
extraction and lets a second sheet run unbounded bounds nothing. A streaming format is not capped at all — nothing
accumulates, so a ceiling there would exist only to be raised. An uncapped buffering export is a
build warning (`TQL-LD-5310`).

**`groupBy:`** lets a workbook report template read the rows as ordered groups, each with its
`key` and its own `rows` — one group is held at a time, so a grouped report is not a
materialized one. A jxls report can put each group on its own sheet with `multisheet`:

```
jx:each(items="groups" var="g" multisheet="groupKeys" lastCell="A3")
jx:each(items="g.rows" var="r" lastCell="A3")
```

A print (pdf) template reads no groups — its model carries the rows — so `groupBy:` on a pdf
export is an inert key the build reports (`TQL-YAML-1005`); group in the query, or split.

**`splitBy:`** goes further and writes one *document* per group, delivered as a single ZIP. This
is what a printable document does instead of streaming: page numbers stay per document, a
partly-empty last page is the end of a document rather than a seam, and a footer total is that
group's total. `filename:` must carry `{key}`:

```yaml
export:
  format: pdf
  template: invoice.html
  filename: invoice-{key}.pdf
  splitBy: customer_id
```

One file still leaves the export, so downloads, push destinations and mail attachments are
unchanged. That file is the bundle: `invoice-{key}.pdf` downloads as `invoice.zip` with
`Content-Type: application/zip`, on `query-export` and `file-export` alike. A `file-export`
transfer records the bundle — its status reports `invoice.zip` as the `filename`, and the
operations console lists the transfer as `zip`. The type follows the recorded format, never the
name: a csv an author called `notes.zip` is still served as `text/csv`. The bundle's name is the
declared stem without the placeholder and the separators around it, wherever the placeholder
stands: `{key}.users.csv` bundles as `users.zip`, `users-{key}-daily.csv` as `users-daily.zip`.
Every entry is stamped `1980-01-01`, the ZIP epoch, so two identical exports are byte-identical and
Info-ZIP `unzip` reads a non-ASCII entry name correctly. A blank `splitBy:` is no
split. One group still produces a ZIP and no rows produce an empty one — the output shape is a
property of the route, not of today's data.

**Each document reads its own values.** A source whose rows carry the split column is narrowed
to that document; one that does not is shared by all of them. The author states which is which by
selecting the column:

```yaml
export:
  splitBy: customer_id
  filename: invoice-{key}.pdf

sources:
  customer:
    sql: { file: select-customers.sql }   # selects customer_id → this invoice's customer
  company:
    sql: { file: select-company.sql }     # does not → the same on every invoice
```

One source runs for the whole export, not one per document. A narrowed source inherits the ordering
contract, and unordered rows fail with the source named.

**Both require the extraction to be ordered by the column.** Group boundaries are found on a
single pass, so unordered rows fail with `TQL-LD-2851` rather than writing one group as several;
a missing `order by` is a build warning (`TQL-LD-5311`).

## Asynchronous export: file-export

A `file-export` route (typically `post.yml`) declares its extraction as `sources.main`, exactly
as the synchronous recipe does. Bound request parameters are captured at start and feed the
extraction query. The start request answers `202` with the transfer URLs, and the route owns its
subtree:

- `POST {path}` → `{ "transferId": ..., "statusUrl": "{path}/{transferId}", "fileUrl": "{path}/{transferId}/file" }`
- `GET {path}/{transferId}` — the transfer state: `status` (`RUNNING`, then `COMPLETED`,
  `FAILED` or `STOPPED`), `rowCount`, `filename` (for a `splitBy:` export, the bundle's name),
  `downloaded`, `expired` (the retention sweep has reclaimed the file: the job card renders its
  tombstone, and the file leg answers the same 409 as a run that produced no file), and
  `fileUrl` once completed. `createdAt` says when it started, as an ISO-8601 instant. A `FAILED` export carries `code` — the framework's
  error code the run recorded, such as `TQL-LD-2802` for a document that could not be written
  or `TQL-LD-2810` for a statement that failed — and `reason`, the framework's own sentence for
  it. The driver's text never reaches this face; it is on the execution row, behind the
  operations API. Every URL the status carries is a wire URL, prefixed under a base path like
  the 202's.
- `GET {path}/{transferId}/file` — streams the finished file; an unknown transfer is 404, a
  transfer that is still running (or failed, or stopped, or is an import) is 409, and a
  completed export whose bytes this node cannot open any more is 410 (`TQL-LD-2868`)

`rowCount` means, per state: while `RUNNING`, the rows handed to the codec so far, published
every couple of seconds through a connection of its own so a poller sees it while the
extraction's transaction is still open. On `COMPLETED` it is the rows in the file; on `FAILED`
or `STOPPED`, the rows the run had reached when it ended (the file itself was discarded). An
import counts applied rows instead, and records 0 whenever it rolled back. The job card's poll
cadence backs off from two seconds to ten once the count passes five thousand, on both
directions.

Under [multi-tenancy](multi-tenancy.md) a transfer is the request's SQL: in a per-tenant
isolation mode the extraction, the row statement and the `after:` statement run on the tenant's
pool, an unknown tenant is refused (`TQL-TENANT-4031`) before any transfer row exists, and the
transfer records its tenant so the after-download statement — a later request — runs on the same
pool. The transfer record and the execution verdict stay on `main`.

The `after:` follow-up statement runs once, at one of two timings:

- `extract` (default) — in the same transaction as the extraction query. Reliable: the rows are
  marked exactly when they are extracted, so a re-run cannot extract them twice.
- `download` — once, with the first-download claim. The claim is taken when a GET opens the
  file, before any byte is sent; a HEAD never takes it; the statement and the claim commit
  together, so a statement that fails leaves the transfer undownloaded and the next GET tries
  again. Later fetches stream the file without re-running it. Use this when "handed over" means
  "a client fetched the file" — a GET that was cut off after the first byte counts.

What the follow-up made stale is declared on the route as on any other writer: `emit:` names
the live-view topics ([realtime.md](realtime.md)) and `invalidates:` the tables whose catalogs
and held results ([response-shaping.md](response-shaping.md#holding-a-result)) the statement
changed. Both fire when the `after:` statement commits — with the extraction under `extract`,
with the first-download claim under `download` — and never on a rollback, a stopped run, a HEAD
or a later fetch, which run nothing. A `file-export` with no `after:` writes nothing and cannot
declare either (`TQL-YAML-1038`, `TQL-FIELD-4620`): a list with `refreshOn:` over the exported
rows would have nothing to refetch. The transfer records what its follow-up announces when it
starts, so every first fetch announces alike — the route's own file leg and the operations
console's transfers page, which serves every application's transfers and knows no route.

```yaml
export:
  format: csv
  after:
    sql:
      file: mark-extracted.sql   # update orders set extracted = true where ...
emit: orders.changed             # the orders list refreshes when the mark lands
invalidates: [orders]            # and a held read over orders is dropped
```

## Asynchronous import: file-import

```yaml
version: tesseraql/v1
id: items.import
kind: route
recipe: file-import

security:
  auth: bearer
  policy: items.write

import:
  format: csv
  columns: [name, qty]
steps:
  - id: row
    sql:
      file: upsert-item.sql
```

with a colocated per-row statement whose parameter names are the column names:

```sql
insert into items (name, qty)
values ( /* name */ 'sample', cast( /* qty */ '1' as integer) )
on conflict (name) do update set qty = excluded.qty
;
```

The uploaded file is the `POST` body — either the raw file content, or `multipart/form-data`
(a part named `file` is preferred, otherwise the first file part). The upload spools to disk
before the request returns — into `tmp/tesseraql/uploads` inside the application's work
directory, beside the temp store's own scratch, so `tesseraql.app.work` moves both together and
arbitrarily large files never sit in memory. The runtime creates that directory at startup and
refuses to start if it cannot write there, because every form post spools through it. It does ride
the runtime's request-body bound, `tesseraql.http.maxBodyBytes` (10 MB by default), and a file
over it is refused with a `413` naming the key. A workbook is several times the bytes of the
same rows as text, so the same feed reaches that bound sooner as `format: excel` than as
`format: csv`. An empty upload is rejected (`TQL-LD-2820`).

## Validate before you write: `review: required`

An import can ask before it writes. `import.review: required` splits the upload in two:

```yaml
import:
  format: csv
  columns: [sku, supplier, price]
  onError: skip
  review: required
```

The upload parses and validates the whole file and writes nothing. The answer is a report —
how many rows are ready, which were rejected and why — plus a confirm token, and a second
request spends that token to run an ordinary import of exactly what was reviewed. A file with
nothing importable answers `422` and no token, so the confirm affordance and the status code
can never disagree.

`onError:` decides what a partly-invalid file offers. Under `skip` the valid rows are a
committable set, so the report comes with a confirm; under `rollback` (the default) there is
nothing to commit, so it comes without one. A parked batch is one principal's to confirm, which
is why the route needs a `security.auth:` declaration, and it expires — `tesseraql.transfers
.reviewTtl`, thirty minutes by default — after which the answer is to upload again.

On a file-import route `input:` is the **row** contract: what each row must satisfy, not what
the request must. The same vocabulary a form declares — `required`, the bounds, `pattern`,
`format:`, `enum`, `codes:` — applies per row, and the report names the line, the column and
the value it refused.

A reviewed import needs its parked bytes to be readable from the node that serves the confirm.
The default temp store is node-local, so a multi-node deployment wants
`tesseraql.temp.store: db` or `blob`.

## The page

A `file-import` route can answer a page as well as JSON. Point `response.html.view` at a
`recipe: import` view document, and put a `page` route at the same URL to render the empty
form ([declarative-views.md](declarative-views.md)):

```yaml
# get.yml — the empty upload form
recipe: page
response: { html: { view: prices-import } }

# post.yml — the import, answering the same document
recipe: file-import
response: { html: { view: prices-import } }
```

The page is the file-upload form, the report the parse answered, and the confirm form when
there is something to confirm. Confirming answers the job card described above. Without
JavaScript every leg still works: the forms post natively and the server answers whole pages.

The statement runs once per parsed row, all inside one import. What happens to a failing row is
the `onError:` choice:

- `rollback` (default) — all or nothing. Any failing row rolls the whole import back; the
  transfer ends `FAILED` and the status response lists every rejected row with its row number
  and message (up to a reporting cap).
- `skip` — clean rows commit. The transfer ends `COMPLETED`, `rowCount` counts the applied
  rows, and the rejected rows are listed the same way.

Import-side `import:` keys beyond `format`, `columns`, and `onError` (the per-row statement is a
`steps:` entry, not an `import:` key):

- `headerRow:` (default `true`) — whether the table starts with a header row. With a header,
  simple-form columns match by header label; `label:` matches a localized label to a SQL
  parameter name; omitting `columns:` entirely uses the header labels as parameter names.
- `domain:` on a column — the type and pattern from a field domain, exactly as on an export
  column above; the block's `locale:` still drives the parse.
- `startRow:` — the 1-based row the table starts at, for files with title rows above the data.
- `sheet:` — workbook formats: the sheet to read (default: the first).
- `locale:` — drives `type:`/`format:` parsing of dates and numbers: a literal,
  `principal.claim.locale`, or `request.locale`, with the same configuration fallback as
  exports. A file-import route binds no request inputs, so a `query.`/`body.` source cannot
  resolve here.
- `column:` on a column (`D` or a 1-based number) reads an explicit position instead of
  matching headers.

Status polling works exactly as for exports (same `{path}/{transferId}` shape), minus the file
URL.

## Formats and optional modules

`csv` is built in. The other formats are opt-in modules resolved through the standard module
mechanism ([getting-started](getting-started.md#opt-in-modules-drivers-and-codecs)):

```bash
tesseraql modules add io.tesseraql:tesseraql-excel --app .   # excel, import and export
tesseraql modules add io.tesseraql:tesseraql-pdf --app .     # pdf, export only
```

A format whose module is not on the classpath fails with `TQL-LD-2801`. PDF is output-only:
`file-import` rejects it (`TQL-LD-2830`).

## Security

There is nothing special to do: the route's `security:` block applies to the whole subtree —
the start request, the `{transferId}` status endpoint, and the `{transferId}/file` download are
all guarded by the same declaration. Query routes' data-scoping rules apply to extraction
queries like any other query.

A transfer records who started it — the requesting principal's subject, on the route's export
start, its one-shot import and its reviewed commit; nothing for a polled import, a job step's
export or a public route's caller ([job-inbox.md](job-inbox.md)). That is an owner for the
surfaces that list a user's own transfers, never a reader gate: whoever the route admits reads
the subtree, so a colleague under the same policy can still fetch a link the exporter shared.

## Error codes

| Code | Meaning |
| --- | --- |
| `TQL-YAML-1063` | An `export:`, `import:` or `tesseraql.files.*` literal the runtime cannot honour where it reads it — a zone, a language tag, a `csv`/`pdf` column pattern, an import column type, a cell reference, a mixed-case format name, or a request source naming nothing the surface binds. The linter's error and the boot refusal carry the same code |
| `TQL-YAML-1005` | A declared key the format never reads (`bom:`, `sheet:`, `startCell:`, `template:` on the wrong format, `locale:` on a workbook, a `type:` the export does not render): a lint error, a boot warning. As a warning: a declaration honoured less than it reads |
| `TQL-YAML-1041` | An incomplete export or import: a `file-export` route with no `export:` block, an `after:` without its statement or on a `query-export` (which has no hook — only `file-export` supports one), `splitBy:` without `{key}`, an export recipe with no `main` source file to read, a `file-import` route with no `import:` block, no `steps:` entry, or a row step naming no `file:`. The linter's error and the boot refusal carry the same code |
| `TQL-YAML-1006` | The export names a template that is not there, or the wrong kind of file for the format, at lint and at boot |
| `TQL-LD-2801` | No codec for the declared format (the module is not installed) |
| `TQL-FIELD-2001` | A request-sourced zone or locale the server cannot use (`code: timezone` / `locale`), refused before any SQL runs — 400 |
| `TQL-LD-2802` | The document could not be written after the extraction ran — a codec, a column format or the spool; the message names the format and the file — 500 |
| `TQL-LD-2836` | An Excel export holds what a workbook cannot: a cell over 32,767 characters, a worksheet past 1,048,576 rows or 16,384 columns, or a report cell the workbook refused — the message names the column and the data row, or the cell — 500 |
| `TQL-LD-2837` | An Excel export's declared `template:` is not a workbook when the export runs — missing, a directory, empty or not an xlsx/xls file — named by its path — 500 |
| `TQL-LD-2810` | The file-transfer service failed — creating its schema, recording a transfer, running an export step, the extraction's first fetch (`Export query failed`) or the `after:` statement (`Export follow-up statement failed`); the message carries the cause |
| `TQL-LD-2820` | `file-import` received an empty request body |
| `TQL-LD-2821` | The file transfer service is not configured in this runtime |
| `TQL-LD-2822` | Unknown transfer id (status or download) — 404 |
| `TQL-LD-2823` | The transfer has no downloadable file yet (still running, failed, stopped, or an import) — 409 |
| `TQL-LD-2868` | A completed export whose produced file this node cannot open — a node-local `file` temp store on a stack whose members share the transfer table, a spool an external cleaner removed. The message names the store; a rerun produces the file again — 410 |
| `TQL-LD-2824` / `TQL-LD-2825` | Poll-driven import variants — see [connectors.md](connectors.md) |
| `TQL-LD-2830` | PDF is output-only; `file-import` cannot read it |
| `TQL-LD-2850` | A format that holds every row passed its `maxRows:` |
| `TQL-LD-2851` | Group keys are not in order — the extraction needs an `order by` on the `groupBy:` / `splitBy:` column |
| `TQL-LD-2852` | A placement export's rows reached template content below the data area |
| `TQL-LD-2853` / `TQL-LD-2854` / `TQL-LD-2855` | A row value, shape or spool the re-readable row set could not carry |
| `TQL-LD-2856` | A codec asked for a row source its streaming declaration does not match |
| `TQL-LD-2857` | Two `splitBy:` keys name the same file once made safe for a filesystem — the same name outright, or the same name up to case, which a case-insensitive filesystem reads as one file; the message names both keys |
| `TQL-LD-2858` | A `splitBy:` export's `filename:` carries no `{key}` |
| `TQL-YAML-1041` | A malformed `export:` **pipeline step** — no arm to read the rows, no format, or a `download`-timed follow-up ([the export step](jobs.md#the-export-step)) |

A scheduled job can produce a file through the same vocabulary — the
[`export:` pipeline step](jobs.md#the-export-step) runs the extraction inline on the
job's datasource, records the same transfer rows, and the operations console's
transfers page links the completed file.

## Retention

Produced files accumulate — a daily report is 365 files a year per job — so the
transfer store takes a retention policy:

```yaml
tesseraql:
  transfers:
    retentionDays: 30        # nothing expires by default
    sweepInterval: 1h        # how often the sweep looks (default 1h)
```

Files older than `retentionDays` are reclaimed on a periodic sweep: the spooled bytes
are deleted, the transfer row **stays as history** (flagged *expired* on the
operations console's transfers page), and the download answers "no downloadable file"
from then on. Nothing expires by default — the same stance lake-table snapshots take:
retention policy belongs to the app. Every node may sweep; reclaiming is idempotent,
and with the default node-local `tesseraql.temp.store: file` each node frees its own
disk — cluster deployments want `db` or `blob`, for retention and for cross-node
downloads alike.

## Related pages

- [printable-documents.md](printable-documents.md) — the `pdf` codec and print templates
- [attachments.md](attachments.md) — durable per-record files, as opposed to tabular transfers
- [connectors.md](connectors.md) — the `poll:` trigger: SFTP/FTPS/local directory-driven imports
- [reference-yaml-surface.md](reference-yaml-surface.md) — the full key-by-key YAML reference
- [transactional-writes.md](transactional-writes.md) — the 2-way SQL parameter syntax used by per-row statements
