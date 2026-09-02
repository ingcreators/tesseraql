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

An uploaded import rides the runtime's request-body bound,
`tesseraql.http.maxBodyBytes` (default 10 MB; see deployment.md) — a feed larger than that
needs the bound raised, and the refusal is a 413 naming the key rather than a mystery.

`GET {path}/{transferId}` reports a transfer's state, and answers two ways: JSON for an API
caller, and a self-polling job card for a browser. The card carries its own polling attributes,
writes the cadence the server chose, and a terminal card carries no trigger at all — which is
how the polling stops. An id this runtime does not know is a `404` for the API and a `200`
tombstone for the card, because a poller that receives an error keeps polling an error.
`POST {path}/{transferId}/cancel` asks a running import to stop; the request is a flag its row
loop reads between rows, so the stop lands at a row boundary and leaves nothing written — an
import is one transaction, and a stop before the commit takes every applied row with it.

Every transfer is also tracked as a batch execution, so imports and exports show up app-scoped
in the [operations console](ops-console.md). Imports can alternatively be driven by polling a local or SFTP/FTPS
directory instead of an HTTP upload — see [connectors.md](connectors.md).

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
filename. The whole `export:` block is optional — without it you get CSV, every query column,
column names as headers, and `<route id>.csv` as the filename.

Every recipe reads the same way: `export:` says how rows are written and never what to read, so
the extraction is a source like any other. An `export.after` block on `query-export` is a
compile-time error (`TQL-ROUTE-3101`) — follow-up statements need `file-export`.

## The export: block

```yaml
export:
  format: excel               # csv (built in) | excel | pdf (optional modules)
  filename: orders.xlsx       # default: <route id> + the format's extension
  locale: de-DE               # or a request source, e.g. principal.claim.locale
  timezone: Asia/Tokyo
  columns:
    - name                    # simple form: column name is also the header
    - { name: held_on, label: Held on, type: date, format: yyyy/MM/dd }
    - { name: fee, type: number, format: "#,##0.00" }
  template: orders.xlsx       # workbook/print template colocated with the route
  sheet: Orders               # workbook formats: the sheet to write
  startCell: B5               # workbook placement mode: where data rows start
  maxRows: 5000               # formats that hold every row: the ceiling (see below)
  groupBy: department         # template reads the rows as groups (see below)
  splitBy: customer_id        # one document per value, bundled as a ZIP (see below)
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

- `columns:` selects and orders the exported columns; omit it to export every query column with
  its name as the header. `label:` sets the label in the file (it may be localized text).
- `type:` (`date` / `datetime` / `number`) with `format:` renders values through a date or
  decimal pattern — and, for workbooks, a matching cell format — instead of raw text.
- `locale:` and `timezone:` drive those patterns. Each accepts a literal value or a request
  source such as `principal.claim.locale`, `query.tz`, or `request.locale` (the negotiated
  request locale), so the requesting user decides how dates and numbers render. When a route
  declares neither, the app configuration keys `tesseraql.files.locale` and
  `tesseraql.files.timezone` apply.
- Excel output has three template modes: no `template:` renders a plain grid; a template plus
  `startCell:` is placement mode — the template carries layout and styles while the YAML says
  where each column lands (`- { name: qty, column: D }`); a jx:-annotated template without
  `startCell:` is a full jxls report. PDF output uses a colocated XHTML print template instead —
  see [printable-documents.md](printable-documents.md).
- A template path that does not exist fails the build rather than quietly producing a plain
  grid, and `startCell:` without a template is refused: the mode a declaration selects should be
  the mode it names.

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

**`groupBy:`** lets a template read the rows as ordered groups, each with its `key` and its own
`rows` — one group is held at a time, so a grouped report is not a materialized one. A jxls
report can put each group on its own sheet with `multisheet`:

```
jx:each(items="groups" var="g" multisheet="groupKeys" lastCell="A3")
jx:each(items="g.rows" var="r" lastCell="A3")
```

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
unchanged. One group still produces a ZIP and no rows produce an empty one — the output shape is
a property of the route, not of today's data.

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
- `GET {path}/{transferId}` — the transfer state: `status` (`RUNNING`, then `COMPLETED` or
  `FAILED`), `rowCount`, `filename`, `downloaded`, and `fileUrl` once completed
- `GET {path}/{transferId}/file` — streams the finished file; an unknown transfer is 404, a
  transfer that is still running (or failed, or is an import) is 409

The `after:` follow-up statement runs once, at one of two timings:

- `extract` (default) — in the same transaction as the extraction query. Reliable: the rows are
  marked exactly when they are extracted, so a re-run cannot extract them twice.
- `download` — once, on the first successful file fetch. Later fetches stream the file again
  without re-running it. Use this when "handed over" means "actually downloaded".

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
before the request returns, so arbitrarily large files never sit in memory — but it does ride
the runtime's request-body bound, `tesseraql.http.maxBodyBytes` (10 MB by default), and a file
over it is refused with a `413` naming the key. A workbook is several times the bytes of the
same rows as text, so the same feed reaches that bound sooner as `format: excel` than as
`format: csv`. An empty upload is rejected (`TQL-LD-2820`).

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
- `startRow:` — the 1-based row the table starts at, for files with title rows above the data.
- `sheet:` — workbook formats: the sheet to read (default: the first).
- `locale:` — drives `type:`/`format:` parsing of dates and numbers, with the same literal /
  request-source / configuration fallback rules as exports.
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

## Error codes

| Code | Meaning |
| --- | --- |
| `TQL-ROUTE-3101` | A `query-export` route declares an `export.after:` block, which only `file-export` supports |
| `TQL-LD-2801` | No codec for the declared format (the module is not installed) |
| `TQL-LD-2810` | The transfer bookkeeping schema could not be created |
| `TQL-LD-2820` | `file-import` received an empty request body |
| `TQL-LD-2821` | The file transfer service is not configured in this runtime |
| `TQL-LD-2822` | Unknown transfer id (status or download) — 404 |
| `TQL-LD-2823` | The transfer has no downloadable file yet (still running, failed, or an import) — 409 |
| `TQL-LD-2824` / `TQL-LD-2825` | Poll-driven import variants — see [connectors.md](connectors.md) |
| `TQL-LD-2830` | PDF is output-only; `file-import` cannot read it |
| `TQL-LD-2850` | A format that holds every row passed its `maxRows:` |
| `TQL-LD-2851` | Group keys are not in order — the extraction needs an `order by` on the `groupBy:` / `splitBy:` column |
| `TQL-LD-2852` | A placement export's rows reached template content below the data area |
| `TQL-LD-2853` / `TQL-LD-2854` / `TQL-LD-2855` | A row value, shape or spool the re-readable row set could not carry |
| `TQL-LD-2856` | A codec asked for a row source its streaming declaration does not match |
| `TQL-LD-2857` | Two `splitBy:` keys name the same file once made safe for a filesystem |
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
