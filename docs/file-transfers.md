# File import and export

Three route recipes move tabular files between HTTP and the database. `query-export` streams a
generated file back as the response — a plain download link. `file-export` and `file-import` run
asynchronously: the request starts a transfer and returns immediately, and the route owns a small
URL subtree for tracking it. All three share the same `export:`/`import:` vocabulary — formats,
column mappings, format patterns, locale and time zone — so a synchronous download can become an
asynchronous extraction by changing the recipe, not the column layout.

## Choosing a recipe

- **`query-export`** — synchronous. `GET` the route, get the file. The extraction query lives in
  the route's ordinary `sql:` block, and rows stream from a database cursor through the codec
  into the response, so even large results never buffer in memory. Use it for downloads a user
  clicks. It cannot run follow-up statements.
- **`file-export`** — asynchronous. The request returns `202 Accepted` with a transfer id; the
  extraction runs in the background and the finished file is fetched later. Use it for large or
  slow extractions, and whenever you need an `after:` follow-up statement (for example, marking
  rows as extracted).
- **`file-import`** — asynchronous. The uploaded file is the request body; parsing and the
  per-row SQL run in the background while the client polls for the outcome, including per-row
  rejections.

Every transfer is also tracked as a batch execution, so imports and exports show up app-scoped
in the operations console. Imports can alternatively be driven by polling a local or SFTP/FTPS
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

Two things are compile-time errors on `query-export` (`TQL-CAMEL-3101`): an `export.sql` block
(the query belongs in the route's `sql:` block) and an `export.after` block (follow-up
statements need `file-export`).

## The export: block

```yaml
export:
  format: excel               # csv (built in) | excel | pdf (optional modules)
  filename: orders.xlsx       # default: <route id> + the format's extension
  locale: de-DE               # or a request source, e.g. principal.claim.locale
  timezone: Asia/Tokyo
  columns:
    - name                    # simple form: column name is also the header
    - { name: held_on, header: Held on, type: date, format: yyyy/MM/dd }
    - { name: fee, type: number, format: "#,##0.00" }
  template: orders.xlsx       # workbook/print template colocated with the route
  sheet: Orders               # workbook formats: the sheet to write
  startCell: B5               # workbook placement mode: where data rows start
  sql:                        # file-export only: the extraction query
    file: select-orders.sql
  after:                      # file-export only: the follow-up statement
    timing: extract           # extract (default) | download
    sql:
      file: mark-extracted.sql
```

- `columns:` selects and orders the exported columns; omit it to export every query column with
  its name as the header. `header:` sets the label in the file (it may be localized text).
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

## Asynchronous export: file-export

A `file-export` route (typically `post.yml`) keeps its query inside the `export:` block, as
above. Bound request parameters are captured at start and feed the extraction query. The start
request answers `202` with the transfer URLs, and the route owns its subtree:

- `POST {path}` → `{ "transferId": ..., "statusUrl": "{path}/{transferId}", "fileUrl": "{path}/{transferId}/file" }`
- `GET {path}/{transferId}` — the transfer state: `status` (`RUNNING`, then `COMPLETED` or
  `FAILED`), `rows`, `filename`, `downloaded`, and `fileUrl` once completed
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
before the request returns, so arbitrarily large files never sit in memory; there is no
framework size cap on transfer uploads. An empty upload is rejected (`TQL-LD-2820`).

The statement runs once per parsed row, all inside one import. What happens to a failing row is
the `onError:` choice:

- `rollback` (default) — all or nothing. Any failing row rolls the whole import back; the
  transfer ends `FAILED` and the status response lists every rejected row with its row number
  and message (up to a reporting cap).
- `skip` — clean rows commit. The transfer ends `COMPLETED`, `rows` counts the applied rows,
  and the rejected rows are listed the same way.

Import-side `import:` keys beyond `format`, `columns`, `onError`, and `sql`:

- `headerRow:` (default `true`) — whether the table starts with a header row. With a header,
  simple-form columns match by header label; `header:` matches a localized label to a SQL
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
mechanism ([app-developer-distribution.md](app-developer-distribution.md)):

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
| `TQL-CAMEL-3101` | A `query-export` route declares `export:` keys only `file-export` supports (`sql:`, `after:`) |
| `TQL-LD-2801` | No codec for the declared format (the module is not installed) |
| `TQL-LD-2810` | The transfer bookkeeping schema could not be created |
| `TQL-LD-2820` | `file-import` received an empty request body |
| `TQL-LD-2821` | The file transfer service is not configured in this runtime |
| `TQL-LD-2822` | Unknown transfer id (status or download) — 404 |
| `TQL-LD-2823` | The transfer has no downloadable file yet (still running, failed, or an import) — 409 |
| `TQL-LD-2824` / `TQL-LD-2825` | Poll-driven import variants — see [connectors.md](connectors.md) |
| `TQL-LD-2830` | PDF is output-only; `file-import` cannot read it |

## Related pages

- [printable-documents.md](printable-documents.md) — the `pdf` codec and print templates
- [attachments.md](attachments.md) — durable per-record files, as opposed to tabular transfers
- [connectors.md](connectors.md) — the `poll:` trigger: SFTP/FTPS/local directory-driven imports
- [reference-yaml-surface.md](reference-yaml-surface.md) — the full key-by-key YAML reference
- [transactional-writes.md](transactional-writes.md) — the 2-way SQL parameter syntax used by per-row statements
