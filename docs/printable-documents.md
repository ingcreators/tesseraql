# Printable documents (PDF)

Roadmap Phase 21. The optional `tesseraql-pdf` module adds a `pdf` codec behind the standard
file-codec SPI: any `query-export` or `file-export` route can stream a printable document
instead of a tabular file. Putting the jar on the classpath is the whole install
(ServiceLoader, design ch. 28, 47). PDF is output-only: `file-import` rejects it
(`TQL-LD-2830`).

## A printable route

```yaml
version: tesseraql/v1
id: users.print
kind: route
recipe: query-export

security:
  auth: bearer
  policy: users.read

sql:
  file: print.sql

export:
  format: pdf
  filename: users.pdf
  template: print.html        # colocated with the route; omit for the built-in grid
  columns:
    - { name: name,   header: 氏名 }
    - { name: status, header: 状態 }
```

Without a `template:` the built-in grid lays the rows out as a plain A4 table with repeating
column headers and `Page n / total` numbering — useful for ad-hoc listings. With a template,
the document is fully app-designed.

`file-export` works the same way for asynchronous extraction (`{path}/{transferId}/file`
downloads the finished document), including `after:` follow-up statements.

## Print templates

A print template is an app-authored XHTML file (well-formed XML, `.html`), colocated with the
route and rendered through the standard template engine (design ch. 12) before PDF conversion.
The model is:

- `rows` — the query rows, values already formatted per the column mappings (locale, time
  zone, `format:` patterns — the same rules as CSV output)
- `columns` — `{ name, header }` per declared column
- `fontFamilies` — the app's font families as a CSS `font-family` list, for templates that do
  not name fonts themselves

Page-oriented CSS drives the print layout:

```html
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <style>
    @page {
      size: A4;
      margin: 20mm 15mm;
      @bottom-center { content: "Page " counter(page) " / " counter(pages); font-size: 8pt; }
    }
    body { font-family: 'TesseraQL Sample Gothic'; font-size: 10pt; }
    thead { display: table-header-group; }   /* repeat column headers on every page */
  </style>
</head>
<body>
  <h1>利用者一覧</h1>
  <table>
    <thead><tr><th th:each="column : ${columns}" th:text="${column.header}">h</th></tr></thead>
    <tbody>
      <tr th:each="row : ${rows}"><td th:text="${row.name}">name</td></tr>
    </tbody>
  </table>
</body>
</html>
```

Templates are app-authored and confined: the template must live inside the app home
(`TQL-LD-2832`), every `url(...)` it references resolves only to files inside the app home,
and the network is never fetched during an export. Data interpolates with `th:text`, escaped
by default.

## Fonts (CJK included)

Fonts under the app home's `fonts/` directory (`*.ttf`, `*.otf`) embed automatically, each
registered under the family name carried in the font itself — so
`font-family: 'Noto Sans JP'` works as soon as `NotoSansJP-Regular.ttf` is in `fonts/`.
Registration order is file-name order, deterministic across machines. The examples ship
`TesseraQL Sample Gothic`, a small renamed Noto Sans JP glyph subset (OFL 1.1, see
`examples/user-admin-app/fonts/README.md`); real applications should ship complete fonts.

## Deterministic output

Rendered documents are normalized so exports stay reproducibility-friendly (design ch. 48):
the producer is fixed to `TesseraQL`, creation/modification dates and XMP metadata are
dropped, and the trailer `/ID` derives from a fixed seed. The same rows through the same
template yield byte-identical PDFs.

## Engine and licensing

The renderer is [openhtmltopdf](https://github.com/openhtmltopdf/openhtmltopdf) (LGPL),
adopted at the design ch. 50 decision point for its full page-oriented CSS support. The LGPL
dependency never leaks into applications that do not print: `tesseraql-pdf` is an opt-in
module - no runtime module depends on it, and without the jar a `format: pdf` route fails
loudly at build time (`TQL-LD-2801`). Inside the module the engine sits behind the
`PdfEngine` ServiceLoader SPI with the `tesseraql.pdf.engine` system property (default
`openhtml`), so a replacement stack can ship as a drop-in jar without touching the codec.

## Testing and coverage

A suite case that exercises the route's extraction SQL proves the data the document renders:

```yaml
- name: the printable user list extraction returns sato
  sql:
    file: web/api/users/print/print.sql
  params: {}
  expect:
    rowCount: 1
    rows:
      - name: sato
```

The `document` coverage kind declares every route exporting a printable document and counts
it covered when a suite case exercises one of its SQL artifacts; gate it with
`coverage.thresholds.document`.

## Lint

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-YAML-1005` | error | a pdf export declares workbook-only options (`sheet:`, `startCell:`) |
| `TQL-YAML-1006` | error | the pdf template is not an `.html` file, or is missing |

## Error codes

| Code | Meaning |
| --- | --- |
| `TQL-LD-2830` | PDF is output-only; `file-import` cannot read it |
| `TQL-LD-2831` | rendering failed (bad template markup, engine error) |
| `TQL-LD-2832` | the template lies outside the app resource root |
| `TQL-LD-2833` | `tesseraql.pdf.engine` names no available engine |
| `TQL-LD-2834` | a font under `fonts/` cannot be read |
