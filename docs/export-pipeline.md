# The export pipeline

Status: **wave 1 complete 2026-08-10** (slices 1-10, #705-#715). **Wave 2 designed 2026-08-10** —
slices 11-12, below.

Everything between a query and a delivered file: how rows reach a codec, what a template is allowed
to see, and what happens when there are a great many rows. The three concerns turned out to be one
surface. A codec receives a row iterator and nothing else, which is why a header-and-lines document
has to denormalize; the same signature is why a report template cannot group without materializing;
and the same materialization is what makes a large export a memory question at all. This decides
the shape of that surface once.

## What exists today

### The row paths

**① Materializing query** (`mode: query`). `TesseraqlSqlProducer.readRows` builds a
`List<Map<String, Object>>` of the whole result, bounded by `maxRows` — the route's
`materialize.maxRows`, then `tesseraql.resultMaterialization.maxRows`, then `RouteCompiler`'s
default of 10,000 — and `onOverflow: fail` raises `TQL-LD-0001`. This is what a page or a JSON
response uses, and the cap is right for it.

**② Synchronous export** (`mode: query-export`). `TesseraqlSqlProducer.export` streams: the
`ResultRows` iterator produces one row map at a time, the codec writes it into a spool, and the
response body is the spool's input stream. It applies `StreamingProfiles.forDialect` — a positive
fetch size, auto-commit off on PostgreSQL — so the driver opens a cursor instead of buffering.
**This path is correct.**

**③ Asynchronous export** (the `file-export` recipe and the `export:` step).
`JdbcFileTransferService.runExport` and `exportInline` have the same shape as ②: a `RowIterator`
hands the codec one row at a time. They set auto-commit off. They never set a fetch size —
`prepare()` prepares the statement, applies the timeout, binds parameters, and returns. On
PostgreSQL a fetch size of 0 means the driver reads the entire result into the client before the
first row reaches the iterator; on MySQL the same, since `Integer.MIN_VALUE` is never passed. The
iterator streams over a result set that is already wholly in memory. **This is the largest single
defect in the area**: the memory is spent inside the JDBC driver, where no application-level cap or
codec choice can reach it.

Batch chunk steps are a fourth path and are correct: `JobExecutor` sets auto-commit off on the
reader connection and a fetch size between 100 and 1,000.

### What a codec can see

`FileCodec.write(OutputStream, FileWriteSpec, Iterator<Map<String, Object>>)`, where
`FileWriteSpec` carries columns, sheet, template, `startCell`, the app resource root, locale and
time zone. There is no slot for a model. Report mode puts exactly one entry into the jxls context —
`rows` — and the PDF codec builds a model of `rows`, `columns` and `fontFamilies`. `params` bind the
extraction query and stop there.

A read route is not so limited: `RouteDefinition` carries `sql:`, a `queries:` map of named
additional queries, and `http:` sources, and `RouteCompiler` publishes each under its own key, so a
Thymeleaf template composes all of them.

The asymmetry has consequences that look unrelated until you see the signature:

- A header-and-lines document — an order with its line items, an invoice with its customer —
  must **denormalize the header onto every line**, and the template picks it out of `rows[0]`.
- A report template can only reach groups through jxls's `groupBy`, because a flat `rows` is all
  it has. `groupBy` materializes: `groupIterable` returns `Collection<GroupData>`, whose
  `getItems()` is a `Collection`.
- `multisheet` — jxls's own per-group sheet feature — therefore works only through the path that
  buffers. Its list form (`multisheet="sheetNames"`) cannot resolve at all, since no such context
  entry exists; `EachCommand` falls back to `DynamicSheetNameGenerator` and the per-item expression
  form, which does work.

### The three workbook modes, and how one is chosen

`JxlsFileCodec.write` infers the mode: a template with `startCell` is **placement**, a template
without one is a **jxls report**, no template at all is a **grid**.

| | grid | placement |
| --- | --- | --- |
| engine | fastexcel, streaming | POI `XSSFWorkbook`, whole workbook in memory |
| worksheet | created (`sheet:`, default `data`) | must already exist; a wrong name throws |
| header row | written from `header:` / `name` | not written — the template owns the headings |
| start | A1 | `startCell` |
| columns | declared order, contiguous | `column:` per column, gaps allowed |
| styles | `format:` only | the `startCell` row's styles, `format:` overriding |
| date default | `yyyy-mm-dd hh:mm` applied | whatever the template's style says |

Two things about that inference are not safe:

- **A missing template file falls back silently.** `hasTemplate` is
  `template != null && Files.isRegularFile(template)`, so a mistyped path produces a plain grid
  instead of the designed workbook. A job's `export:` step is protected — `AppLinter` raises
  `TQL-YAML-1006` — but a **route** has only `lintPdfExport`, which checks PDF templates and
  nothing else. One typo silently changes the layout, the styling, and the memory profile.
- **Placement never shifts rows.** It writes downward from `startCell` with `getRow`/`createRow`.
  A template with a total band below the data area is overwritten once the row count reaches it,
  and only in the mapped columns — so the label in column A survives while the amounts in B..D
  become data. The file is corrupt in a way that looks plausible.

### The codecs, and which of them buffer

| format / mode | streams | why |
| --- | --- | --- |
| `csv` | yes | each row is written through as it arrives |
| `excel`, grid | yes | fastexcel's streaming writer |
| `excel`, placement | no | `XSSFWorkbook` holds the whole workbook, and decision 10 records why it stays that way |
| `excel`, report | no | `rows.forEachRemaining(data::add)`, then a POI workbook |
| `pdf` | no | see below |

The PDF codec materializes three times over, and the row list is the smallest of them:
`PdfFileCodec.write` collects the formatted rows, `render` builds one XHTML **string** containing
every row plus its markup, and the engine renders into a `ByteArrayOutputStream` that
`DeterministicPdf.normalize` then round-trips through `PDDocument` and a second `byte[]`.

### No cap on either export path

`maxRows` exists only in `readRows`. Neither `ResultRows` nor `RowIterator` counts rows against a
limit, so a `format: pdf` export of an unbounded query has nothing between it and the heap. The
guard is documented under `materialize:` as though it governed exports.

### A mode that does not exist

The overflow message advises "use pagination, query-stream, or query-export". There is no
`query-stream` mode; the supported set is `query`, `update`, `query-export`.

## Decisions — what a template can see

### 1. The codec receives a model, not a row iterator alone

`FileCodec.write` takes an `ExportModel` in place of the bare iterator. The model carries the row
source, the named query results of decision 2, and the grouping of decision 3. `FileWriteSpec`
keeps its present job — how the columns render — and does not become a bag of data.

The row source appears twice, deliberately: a **single-pass** iterator for codecs that declared
`streams(spec)` (decision 6), and a **re-readable** view for those that did not. Asking for the one
that does not match the declaration fails immediately, so the contract is checked rather than
described. That is what lets a report template iterate its rows more than once while a CSV export
keeps its constant memory.

This is a breaking change to a `ServiceLoader` SPI, taken outright rather than behind an adapter
(mandatory rule 10). It goes in the CHANGELOG.

### 2. Named queries and HTTP sources reach export templates

An export route or step may declare `queries:` and `http:` exactly as a read route does, and both
land in the model under their names. The extraction `sql:` remains the row source; these are what a
document composes *around* the rows — the order header, the totals, the master data a template
labels with. It is the header-and-lines case, answered where the asymmetry was rather than by
denormalizing into the line query.

**`queries:`** run before the extraction, on the same connection and inside the same transaction as
the export query, so a document reads exactly the state its rows came from. They are declared as
**`export.queries:`** rather than at route level, which is where this decision first put them: a
batch export step has no route level, and one idea should not have two shapes.

**`http:` sources** are ordered before the extraction too, which reverses the read-route order
(where they run after the SQL). The reason is not composition but the connection: `runExport` takes
its connection and turns auto-commit off at the top, and after decision 5 it holds a server-side
cursor for the whole write. A network call inside that window pins a pooled connection and an open
transaction for however long the partner takes. Running first means no database resource is held
while waiting.

On the asynchronous path a further constraint decides the design. `FileExportStartProcessor` hands
a plain `ExportRequest` to a background executor — no exchange, no registry, no gateway, only
`params`. So an export's HTTP sources are **called at submission**, in the requesting exchange, and
their results travel in the handoff record. This resolves three problems at once: the call composes
against a real execution context, no I/O happens inside the export transaction, and the caller's
request deadline bounds a partner that hangs — a background transfer has no request to time out,
and `onError: empty` degrades failures but not hangs. It also gives the feature an honest meaning:
the data as of when the export was requested, which is the rule `params` already follow.

**`onError: empty` is rejected on exports** (`TQL-YAML-1006`). On a page, degrading to zero rows
leaves a visible gap for a human. In a document that is archived, mailed and filed, it produces
something that looks complete and is not. An export whose source failed should fail.

A named query or HTTP source on an export with no template is a build warning
(**`TQL-LD-5312`**): CSV and the Excel grid have nowhere to put it, and a source that runs to be
discarded is a cost with no reader.

### 3. Grouping belongs to the framework, not to the template

`export.groupBy: <column>` exposes `groups` in the model: an ordered, lazily-backed sequence of
`{ key, rows }` over the row stream, holding one group at a time.

This is the same mechanism as `splitBy` (decision 12) with a different destination — one asks for
groups inside a document, the other for a document per group — so they share an implementation and
a contract: **the query must be ordered by the column**, and a key that reappears after its group
closed fails with **`TQL-LD-2851`** rather than silently splitting a group in two.

For a jxls report this is what makes `multisheet` composable with streaming: a template writes
`jx:each(items="groups" var="g" multisheet="g.key")` with an inner each over `g.rows`, and never
calls `groupBy`, so nothing materializes. jxls's own `groupBy` keeps working for anyone who wants
it, and keeps buffering; the difference is that a streaming route now has an alternative.

### 4. Mode selection stops being silent

The inference itself stays — `template` plus `startCell` is placement, `template` alone is a
report, neither is a grid — because it reads well and is one less key to teach. What goes is its
tolerance for input that cannot mean what it says:

- A **missing template file is a build error on routes**, as it already is on job steps, by giving
  the route-level export the same `TQL-YAML-1006` check instead of the PDF-only one.
- A **`startCell` with no template is a build error**: placement is a template mode, and the grid
  ignores the key.
- A **placement export whose data would reach non-empty template content below the data area
  fails** with **`TQL-LD-2852`**, naming the row. The template's used range is small and is scanned
  before writing, so the collision row is known in advance and the check survives the streaming
  work of decision 10. Corrupting a total band silently is the worse outcome by a wide margin, and
  it is what happens today.

## Decisions — how many rows can pass

### 5. Every export path applies the dialect's streaming profile

`JdbcFileTransferService.prepare` applies `StreamingProfiles.forDialect` the way
`TesseraqlSqlProducer.export` does. The wiring already exists on both sides: the service's
`vendor()` returns `postgresql` / `mysql` / `mariadb` / `oracle` / `sqlserver`, every one of which
`forDialect` answers, and an unknown vendor gets the conservative default rather than nothing.
Auto-commit is already off on both export methods, which is the other half of PostgreSQL's cursor
condition.

One constraint becomes a rule rather than an accident: MySQL's `Integer.MIN_VALUE` fetch size makes
the connection unusable for other statements until the result set is closed. Both methods already
close the result set through try-with-resources before running the `after: timing: extract`
statement on the same connection. The tests state that ordering so a later edit cannot quietly
break it.

### 6. A codec declares whether it streams, for a given write spec

`FileCodec` gains `boolean streams(FileWriteSpec spec)`, defaulting to `true`. CSV inherits the
default, PDF answers `false`, and Excel answers `spec.template() == null` — grid mode streams,
placement and report modes do not until decisions 9 and 10 change their answers.

The predicate takes the spec because the Excel codec's three modes have three different answers,
and a per-format flag would have to be wrong for two of them. One predicate serves four consumers:
which row source the model populates (decision 1), the row cap (decision 7), the build lint, and
the documentation table.

### 7. The row cap follows the buffering, not the path

An export through a streaming codec stays uncapped: nothing accumulates, and a ceiling there would
exist only to be raised. An export through a buffering codec is exactly as exposed as `mode: query`
and gets the same treatment — `export.maxRows` with `export.onOverflow`, defaulting to
`tesseraql.resultMaterialization.maxRows` and `fail`.

Overflow raises **`TQL-LD-2850`**, distinct from `TQL-LD-0001` because the cause and the remedies
differ: the message names the format and mode that buffer, and points at a streaming format or
`splitBy` rather than at pagination.

A build lint (**`TQL-LD-5310`**, warning) flags an export whose codec buffers and declares no
`maxRows` — the configuration that is uncapped today. It reads the declaration rather than asking
`streams(spec)`: the optional codec modules are not on the linter's classpath, so the predicate
this decision wanted every consumer to share has one consumer it cannot serve. The linter's
javadoc says so, and a codec that streams a templated format can declare `maxRows: -1`.

Rejected: capping every export uniformly. It would put a ceiling on the CSV path that has no
technical reason to exist, and would train authors to raise a number instead of choosing a format
that does not need one.

### 8. Spooled rows are the substrate for everything re-readable

`SpooledRows` in `tesseraql-core` drains a source iterator into a `TempStore` spool once and returns
a fresh reading iterator per pass. It is the re-readable view of decision 1, the backing of
decision 3's groups, and the input side of decision 9. The store is the one the runtime already
provisions, so a deployment that spools to the database keeps doing so.

**The cost here is type fidelity, not storage.** Rows carry raw JDBC values — `BigDecimal`,
`Timestamp`, `byte[]`, temporals — and a lossy round trip changes existing reports' output: a
numeric cell becomes text, a date cell loses its format. `SpooledRows` therefore writes a tagged
encoding over `DataOutputStream`, one type byte per value, covering null, `String`, `Boolean`, the
integral and floating boxes, `BigDecimal`, the `java.time` types drivers return, `java.sql`
`Date`/`Time`/`Timestamp`, and `byte[]`. A value it cannot represent **fails, naming the column**,
rather than degrading to its `toString()`.

Considered and rejected:

- **Java serialization** — round-trips more types, but accepts arbitrary object graphs from the
  driver and opens a deserialization surface for no gain here.
- **JSON / NDJSON** — lossy exactly where it matters: numeric scale and temporal type.
- **MapDB, Chronicle Map** — a dependency, a memory-mapped file discipline, and `--add-opens`
  pressure on the Java 25 target, in exchange for a general `Map` when what is needed is a
  sequential, re-readable row list.
- **A DuckDB temp table** — DuckDB is on the classpath only as an analytics choice; an Excel report
  must not acquire it as a prerequisite.
- **Re-running the query per pass** — no serializer at all, but two to N times the database work
  and no snapshot guarantee across passes.

### 9. jxls report mode becomes streaming

Two changes, and neither alone is worth making. **Output**: `JxlsPoiTemplateFillerBuilder` inherits
`withStreaming(JxlsStreaming)` and jxls 3.1.0 already ships
`SelectSheetsForStreamingPoiTransformer`, so SXSSF output needs no new dependency —
`JxlsStreaming.STREAMING_ON`, with `rowAccessWindowSize` and `compressTmpFiles` available through
`withOptions`. **Input**: jxls's `EachCommand` iterates an `Iterable<?>`, so decision 8's re-readable
view is exactly what it wants, and decision 3's `groups` keeps `multisheet` on the streaming side.

### 10. Excel placement mode does not stream — the recorded answer is "no"

This slice was the one permitted to end in "no", and it did. The reasoning that led here was
half right: SXSSF permits only monotonically increasing row access, and writing downward from
`startCell` looked like it fit.

It does not, for a reason the design missed. SXSSF appends past the last written row and refuses
anything before it — *"Attempting to write a row[4] in the range [0,4] that is already written to
disk"* — while placement writes **into** the template's own data area: the `startCell` row is both
the style prototype and the first data row, and the rows above it are the title and header the
template exists for. Reading the prototype styles before wrapping solves one problem and not this
one. Removing the prototype row first does not help either, because a template with anything at all
below the data area puts the whole range out of reach.

Streaming placement would mean rebuilding the template sheet through the streaming API — copying
every row, cell, merged region, image and print setting by hand. That is a re-implementation with
new fidelity risks, for the mode whose entire purpose is that the template's fidelity is not the
framework's business.

So placement mode keeps `streams(spec) == false`, lives under decision 7's cap, and a
template-styled export declares the number of rows it can carry. The codec's javadoc records this
so the experiment is not repeated.

### 11. PDF is not made streaming

The engine builds a box tree over the whole document to place page breaks and repeat headers, the
XHTML intermediate is one string, and normalization round-trips the result twice. Beyond the
implementation, the format is the reason: `counter(page)` / `counter(pages)`, running headers and
footers are what `OpenHtmlPdfEngine` exists to provide, and a paginated layout is not a stream. A
million-row PDF also has no reader — the viewer fails where the exporter succeeded.

**Chunk-and-merge is rejected** for a single logical table. It is buildable — PDFBox is already a
direct dependency of `tesseraql-pdf`, so `PDFMergerUtility` is at hand — and it does bound the heap,
but it breaks four things the current output gets right:

- page numbers restart per chunk, and `counter(pages)` counts the chunk rather than the document;
- a chunk's last page is partly empty, so every boundary is visible in the merged file, and the row
  count that exactly fills a page is not knowable before layout;
- `OpenHtmlPdfEngine.render` embeds fonts per document through `useFont`, and merging does not
  de-duplicate them — one CJK subset per chunk, trading heap for file size;
- a template footer that totals the data cannot be computed from a chunk.

`DeterministicPdf.normalize` would also still round-trip the merged document through `byte[]` twice,
so the ceiling would move far less than the complexity suggests.

### 12. Large PDF output splits by meaning, not by row count

`export.splitBy: <column>` produces one document per distinct value of that column, delivered as a
single ZIP. It shares decision 3's grouping engine and its ordering contract.

Every objection in decision 11 disappears, because the boundary is one the reader already believes
in: page numbers are per invoice, a partly-empty last page is the end of a document, fonts are
embedded once per document because each document *is* a document, and a footer total is that
group's total.

The contract:

- **The query must be ordered by the split column**, enforced as in decision 3
  (**`TQL-LD-2851`**). A build lint (**`TQL-LD-5311`**, warning) flags a `splitBy` whose SQL has no
  `order by` mentioning that column — a text heuristic over the 2-way SQL, in the shape the mail
  lints already use.
- **The filename templates the group**: `filename: "invoice-{key}.pdf"`. `{key}` is the only
  placeholder, and a `splitBy` without it is a build **error** (`TQL-LD-5311`), because the
  alternative is one file overwriting the next. The substituted value is sanitized to a safe
  filename component, and two values that sanitize alike fail (`TQL-LD-2851`) naming both.
- **The bundle is a ZIP.** One file leaves the export, so the spool, the transfer record, the
  download endpoint, the push destinations and the mail attachment all keep working untouched. One
  group still produces a ZIP and zero rows produce an empty one: the output shape is a property of
  the route, not of today's data.
- **Decision 7's cap applies per group**, which is the number that bounds memory.
- **The `after:` statement runs once for the export**, not once per group. The extraction is one
  query in one transaction however many files it produced.

`splitBy` is codec-agnostic. For a buffering codec it is the memory mechanism; for a streaming one
memory was never the problem and it is a delivery mechanism — one file per branch, per tenant, per
recipient.

### 13. The overflow message stops naming a mode that does not exist

`readRows` advises pagination or `query-export`, which is the whole of the truth.

## Slices

1. **Streaming profile on the asynchronous export path** (decision 5) and the phantom-mode message
   (decision 13). No contract change; the largest effect per line in the campaign. **Done** (#706).
2. **Mode selection stops being silent** (decision 4): route-level template existence, `startCell`
   without a template, and the placement collision check with `TQL-LD-2852`. Independent of
   everything else, and it stops a class of silent corruption. **Done** (#707).
3. **Codec streaming capability and the export cap** (decisions 6, 7): `FileCodec.streams`,
   `export.maxRows` / `export.onOverflow`, `TQL-LD-2850`, and the `TQL-LD-5310` lint. **Done**
   (#709) — the declared ceiling and the rule that applies it were separated so a batch step could
   inherit the bound without acquiring a codec dependency.
4. **`SpooledRows`** (decision 8) in `tesseraql-core` over `TempStore`, with the tagged encoding and
   its round-trip tests. It lands alone because three later slices depend on it. **Done** (#708).
5. **`ExportModel`** (decision 1): the SPI change, every bundled codec migrated, the two row
   sources wired to `streams(spec)`. No author-visible behaviour yet — the slice exists so that the
   next two are additions rather than rewrites. **Done** (#710).
6. **Named queries and HTTP sources in export templates** (decision 2), for jxls reports and PDF
   documents: `queries:` on the extraction connection, `http:` called at submission and carried in
   the handoff record, `onError: empty` rejected, and the `TQL-LD-5312` lint. This is the
   header-and-lines slice. **Done** (#711).
7. **`groupBy`, `groups`, and jxls report streaming** (decisions 3, 9): the ordered grouping engine,
   `withStreaming`, and a `multisheet` template that streams. **Done** (#712) — two things had to be
   learned by running it: streaming *every* sheet includes the template's own, which jxls reads to
   know what to write, so only the generated sheets stream; and a template's expression language
   resolves `g.key` through `getKey()`, not through a record's own accessor.
8. **Excel placement streaming** (decision 10). **Done** (#713) — the answer is "no", recorded in
   decision 10 and in the codec's javadoc so the experiment is not repeated.
9. **`splitBy` and ZIP bundling** (decision 12) over slice 7's grouping engine: `{key}` filenames,
   the per-group cap, the lints. **Done** (#714) — each document follows its codec's own streaming
   declaration, which decision 1's contract caught: a CSV split failed loudly until the per-group
   model matched what CSV declared.
10. **Documentation**: `file-transfers.md` gains what an export template can see and which formats
    stream; `printable-documents.md` gains the header-and-lines shape and `splitBy`; the generated
    reference is regenerated for the new keys and codes. **Done** (#715), along with the three
    amendments this document needed once the code existed: `export.queries:` rather than
    route-level `queries:` (decision 2), the linter's inability to ask `streams(spec)`
    (decisions 6 and 7), and placement's recorded "no" (decision 10).

## Out of scope

- **Streaming PDF output.** Decision 11 is a decision, not a deferral. Revisiting it means changing
  the engine, not the codec.
- **A framework-level per-sheet split key (`sheetBy:`).** Decision 3 gives report templates the
  grouping they need to write `multisheet` themselves. A key that produced sheets for the grid and
  placement modes as well is three different pieces of work behind one name, and it delivers a
  different artefact rather than a cheaper one.
- **A general on-disk `Map`.** `SpooledRows` is a sequential, re-readable row list. Random access by
  key is not needed by any consumer identified here.
- **The `mode: query` cap.** ① is capped correctly; this campaign does not change its default or its
  error.
- **Rendering split documents in parallel.** One group at a time is what keeps peak memory at one
  group.
- **Import-side streaming.** `FileCodec.read` already hands rows to a `RowHandler` one at a time on
  every codec that supports import.

## Testing

- **Streaming profile**: the fetch size and auto-commit state the export path prepares per dialect,
  and the `after: extract` statement still running on the same connection once the result set is
  closed (the MySQL constraint in decision 5).
- **Mode selection**: a route whose Excel template path does not exist fails the build rather than
  producing a grid; `startCell` without a template fails; a placement export whose rows reach a
  template's total band fails with `TQL-LD-2852` naming the row, where today it overwrites the
  mapped columns and leaves the label.
- **The cap**: an export over a buffering codec past `maxRows` fails with `TQL-LD-2850`; the same
  query through CSV succeeds uncapped; the lint fires on an uncapped buffering export and stays
  silent once `maxRows` or `splitBy` is present.
- **`SpooledRows`**: a round-trip property test over every supported type, including `BigDecimal`
  scale, each temporal type, `null`, and `byte[]`; two full iterations returning equal sequences; an
  unrepresentable value failing with the column named.
- **`ExportModel`**: a streaming codec asking for the re-readable view fails, and a buffering codec
  asking for the single-pass iterator fails — the declaration and the model agree or the build of
  the route does not proceed.
- **Named queries**: a PDF order document rendering its header from a named query and its lines
  from the extraction, with the header query reading uncommitted state written earlier in the same
  transaction — the property that justifies running them on the extraction connection.
- **HTTP sources**: a synchronous export composing a stub endpoint's response into its template; an
  asynchronous export whose stub is taken down *after* submission still producing the document,
  proving the call happened at submission rather than in the worker; a stub that fails at submission
  failing the request rather than producing a document with a gap; `onError: empty` failing the
  build. The PDF determinism guard uses a fixed stub, since an HTTP source is an input to the
  same-inputs-same-bytes promise.
- **`groups` and jxls streaming**: a report over a row count that exceeds a deliberately small heap
  in the surefire fork, asserting the same bytes as the buffered implementation produced for a small
  input; a `multisheet` template driven by `groups` streaming, and the same template driven by
  jxls's `groupBy` still working and still buffering, so that closing that gap later is a visible
  change rather than an accident.
- **`splitBy`**: an ordered query producing one document per group inside a ZIP with `{key}`
  filenames; an unordered query failing with `TQL-LD-2851`; a missing `{key}` failing the build; the
  per-group cap firing on one oversized group while its neighbours succeed; a CSV split running
  uncapped; one group and zero rows both producing a ZIP; two values colliding after sanitization
  failing rather than overwriting.
- **Determinism**: the PDF byte-comparison guard keeps holding for each document inside the ZIP.

---

# Wave 2: composition

Wave 1 gave a codec a model. Using it against real reporting requirements showed three things the
model gets wrong, and they share a cause: the extraction and the named queries were made to look
like different kinds of thing, when the only real difference between them is which one the export
is *about*.

## What wave 1 left

**The export's shape does not match a route's.** On a route the default result lands under `sql`
and a named one under its name, both shaped `{rows, rowCount}`. In an export the extraction became
a bare `rows` and a named query stayed `{rows, rowCount}`, so one template reads `${rows}` and
`${header.rows}` side by side and has to know why. That divergence was introduced here, not
inherited.

**Named query results are materialized.** Wave 1 spooled the extraction and left named queries as
`List<Map>`, so a workbook whose second sheet is a large named query is exactly the memory problem
this campaign set out to remove, entering through a door it opened itself. The row cap does not
count them either.

**A split export writes the same values into every document.** `SplitExport` passes one `values`
map to every group, so five hundred invoices split by customer print the same customer. The only
way out today is to denormalize the header onto every line and read `rows[0]` — the pattern
decision 2 exists to end. The flagship case of `splitBy` is the one it serves worst.

## Decisions

### 14. An export's model is shaped like a route's context

The extraction is published as **`sql`**, with `rows` and `rowCount`, exactly as a named query is.
A template reads `${main.rows}` and `${header.rows}`, and the only difference left between them is
the one that is real: which result the export is *about*.

`rowCount` is answerable because a template mode is a buffering mode — its rows are spooled, and a
spool knows its size. A streaming codec has no template and reads neither.

This breaks existing report and print templates, which is what pre-1.0 is for (mandatory rule 10).
The blast radius is one gallery template, the framework's own bundled PDF grid, and the tests. It
buys one sentence of explanation instead of two: *an export's template sees what a route's template
sees*.

Rejected: publishing `rows` **and** `main.rows`. Two names for one thing is worse than either name.

### 15. Every result an export carries is re-readable and capped

Named query results are drained into a `SpooledRows` like the extraction, keeping the
`{rows, rowCount}` shape a template reads. Nothing changes in a template; what changes is that a
second sheet of a hundred thousand rows costs a spool instead of a heap.

The row cap counts them too. A cap that bounds the subject and lets a named query run unbounded
bounds nothing — the sheet that overflows is as likely to be the summary as the detail.

This is also the prerequisite for decision 16: a value can only be narrowed per document if it can
be read more than once.

### 16. A split export narrows the values that name its groups

For each document, a named result whose rows carry the **split column** is narrowed to that group's
rows; one that does not carry it is shared whole.

That rule reads directly from what the query selected, so an author states the relationship by
selecting the column rather than by declaring anything:

```yaml
export:
  splitBy: customer_id
  filename: invoice-{key}.pdf
  queries:
    customer: { file: select-customers.sql }   # selects customer_id → per document
    company:  { file: select-company.sql }     # does not → the same in every document
```

One query runs for the whole export, not one per document: five hundred invoices cost one customer
query, not five hundred. The narrowing is the same ordered grouping the extraction already uses, so
a named query that carries the split column inherits the ordering contract — unordered rows fail
with `TQL-LD-2851` naming the query.

Rejected: running each named query once per group with the key bound. It reads well and it is an
N+1; the grouping machinery already exists and does not need it.

## Slices

11. **The model mirrors a route's context, and every result is re-readable** (decisions 14, 15):
    `main.rows` in place of `rows`, named results spooled, the cap counting them, and the templates
    that move with it.
12. **Per-document values on a split export** (decision 16), over slice 11's re-readable results.

## Out of scope for wave 2

- **`splitBy` composed with `groupBy`** — one workbook per branch with a sheet per department. It
  fails today (`groupedBy` needs a spool and a split group is a lightweight view). Real, and
  separable from these three.
- **Grouping more than one level deep** — an order, its lines, their shipments. No requirement has
  asked for it yet; wave 2 does not pre-empt one.
- **Named queries against a second datasource** — they run on the extraction's connection by
  construction (decision 2), which is what makes them transactionally honest. An `http:` source
  covers the external case.
- **Renaming `sql:` to something that names its role.** The distinguished binding is a *default* —
  the one a panel's `source:`, pagination, a command's response body and an export's subject point
  at when nothing says otherwise — and that default earns its keep. Its name describes the
  mechanism rather than the role, which is a fair criticism and a framework-wide vocabulary change,
  not an export one.
