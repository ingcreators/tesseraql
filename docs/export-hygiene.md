# Export hygiene: what an export leaves behind, and what it says when it fails

> **Status: complete.** Nine pull requests, in this order, each branched from fresh
> `origin/main` after the previous one merged. **P0** — under `tesseraql.temp.store: db` or
> `blob` a download, a push and the retention sweep address the spool by its own id, a failed
> download is not recorded as delivered, and `tesseraql job run` honours the declared store:
> **shipped with this record** (the pull request that registers this file in both internal-doc
> lists). **P1** the split bundle — every entry carries the extended-timestamp field and the
> ZIP epoch, the key's bound cuts on a code-point boundary and keeps its case, the bundle's name
> drops the placeholder with its separators wherever it stands: shipped as P1. **P2** the spool
> leaks and the 65,535-byte ceiling — a failed drain releases its spool, a failed async or job
> export releases its writer's, a long text spools under a new tag: shipped as P2. **P3** what a
> failed export says — the document code on every arm, the SQL sites coded, the reason within its
> column, the stack logged: shipped as P3. **P4** the Excel codec's own refusals — the 32,767
> cell, the worksheet's row and column limits, a report cell the workbook refuses, a template
> that is not a workbook — `TQL-LD-2836` and `TQL-LD-2837`: shipped as P4. **P5** the zero-row
> header — declared columns or the source's own names, on every surface, through the spool:
> shipped as P5. **P6** the print template's locale — the export's own, English when none, `und`
> folded, the render inside the codec's try: shipped as P6. **P7** the declaration path — the
> predicate refuses a format-less step and a blank format, `push.as:` and an export `filename:`
> are judged for the placeholders the pipeline can resolve, a mismatched extension warns, a codec
> replacing another's format is logged: shipped as P7. **P8** the card and the status JSON under a
> prefix — both links wire URLs by construction, and a failed export's `code` and the framework's
> sentence on the wire and the card: shipped as P8. Each pull request flipped its own line here
> when it merged.
>
> **The two records that filed these items were wrong about them in a dozen places, and the
> most severe defect on the path was in neither.** The twelve items [`download-name-and-bytes.md`](download-name-and-bytes.md)
> and [`export-declarations.md`](export-declarations.md) filed as "export hygiene" were re-measured
> on `5fa510c8b`: ten are live (six wider than filed), two are narrowed by #1306 and #1307, none is
> dead. Beside them the measurement found four unfiled defects; the first outranks every filed
> item. **Read the decisions below, not the filings.** The measurement record
> (`work/export-hygiene-measurement/MEASUREMENT.md`, 830 lines, not in the repository) is the
> evidence; every "RUN" below names a log under `work/export-hygiene-measurement/` (this machine
> only).

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by P0, with the
registration asserted in `InternalDocsSyncTest` (its two set differences are green on a record in
neither list). Site-excluded, so the prose lint and the vocabulary guard never read it; the error
index skips internal documents, so the codes named here move no reference page.

---

## The measured premise

Eight measurers, eight adversaries, three scope lenses and a final adjudicator ran against
`5fa510c8b` (`0.17.0-SNAPSHOT`, the 02:19 UTC `.m2` install) on 2026-09-13. Every verdict was
unanimous; where the lenses disagreed on a fix shape the adjudicator ran the deciding probe.
Surfaces: *route* = the synchronous `query-export`; *async* = `file-export`; *job* = an `export:`
step under the CLI `job run`, the runtime's manual and ops runners, and the scheduler.

### The twelve items, as the user named them

| # | item | verdict on `5fa510c8b` | where it lives | goes to |
|---|---|---|---|---|
| 1 | ZIP host byte | LIVE — every bundle surface; Info-ZIP `unzip` 6.00 only. The mechanism is the ABSENT extra field, not the host byte: `do_string` returns before the UTF-8 flag is read when an entry has no extra field. | `SplitExport.write:82` | P1 |
| 2 | surrogate split + masked 2857 | NARROWED — on the route #1307 made the cut `TQL-LD-2802` naming the filename pattern and put 2857 on the wire as itself; async and job still record the JDK text with no code. The `>100` branch alone lower-cases (since #714, no rationale). | `SplitExport.safe:180-189` | P1 (cut), P3 (arms) |
| 3 | `FileCodecs.discover` last-put-wins | LIVE, wider — a module codec answering `csv` decides the async export, the job step AND every import arm; never the sync route (F82's class-loader split). No log line in any sink. | `TesseraqlRuntime:1045`, `FileCodecs.discover` | P7 (one WARN); the lint and any refusal → F82 slice 2 |
| 4 | the 32,768-character xlsx cell | LIVE in all three modes — the grid writes it, placement fails with the raw POI text, **a jxls report completes with the cell blank**. Beyond 65,535 bytes the spool ceiling fails first. | `JxlsFileCodec` | P4 |
| 5 | the zero-row CSV header | LIVE, wider — csv writes 0 bytes and the Excel grid a cell-less workbook, declared `columns:` or derived, on every arm; the pdf grid already prints a declared header. Reachable today with the shipped `users/export`. | `CsvFileCodec.write:115-121`, `writeGrid` | P5 |
| 6 | the two-part extension | LIVE, wider — wrong whenever `{key}` is not immediately before a single extension (`{key}.users.csv` delivers a dot-file `.users.zip` on a push). | `SplitExport.zipName:56-62` | P1 (name), P7 (lint) |
| 7 | the drain-spool leak | LIVE on every surface and both stores — the drain spool leaks on every buffered codec, any `splitBy:` and any NAMED source; async and job leak the writer spool too. The sweep never reclaims an orphan. | `SpooledRows.drain`, `runExport`/`exportInline` | P2 |
| 8 | the async FAILED reason | LIVE, wider — nine failure shapes answer the identical reason-less status JSON and the import-shaped card; no stack is logged; the job arm's 2000-character cliff ends as a false `TQL-BATCH-4212`. | `JdbcFileTransferService`, `JobRepository`, `FileTransferStatusProcessor` | P3 (record), P8 (wire) |
| 9 | PDF template `Locale.ROOT` | LIVE on every surface — two locales in one document; **any message lookup (`#{key}`) throws** after the query ran, because the render sits outside the codec's try. No shipped template moves under any fix. | `PdfTemplates:33,38`, `PdfFileCodec.write:96` | P6 |
| 10 | `TQL-LD-2856` missing template | NARROWED — dead at lint, boot, registration, `job run`, `rerun` and Studio's manual reload (#1306); live after boot for a deleted, directory, empty or non-workbook template, which fail with the OUTPUT name as source. | `JxlsFileCodec.write:157`, `streams:191` | P4 |
| 11 | the `'null'` format text | LIVE on every job runner — `ExportDeclarations.violations:209-217` returns early on the false belief that a format-less step never runs; `format: ""` on a route is lint-silent and refuses boot naming nothing. | `ExportDeclarations`, `RouteCompiler:1406`/`:1585` | P7 |
| 12 | `push.as:` `{key}` lint | LIVE, wider — the literal name is delivered on every runner; lint is silent, also on an export `filename:` carrying `{key}` without `splitBy:`; an unresolved placeholder renders silently empty. | `PushStepRules`, `ExportRules:194-201`, `StepContext.interpolate` | P7 |

### The four defects the filings did not carry

| id | finding | severity | goes to |
|---|---|---|---|
| **S1** | **Under `tesseraql.temp.store: db` or `blob` no async or job export could be downloaded, pushed or reclaimed** — since #356, in the documented multi-node shape. `download()` and `expireTransfersOlderThan()` built the `SpoolRef` from the TRANSFER id; the keyed stores look the SPOOL id up. Beside it, `download()` claimed the first download BEFORE opening the bytes, so a failed download was recorded as delivered and fired the `afterDownload` SQL. | HIGH | **P0** |
| **S2** | `tesseraql job run` built the node-local file store whatever the app declared (`JobCommand.wire`), recording `file:///` references in the shared table that no served node could resolve. | medium | **P0** |
| 2855 | `SpooledRows.write` spells a String with `writeUTF`, whose ceiling is 65,535 bytes of modified UTF-8: 21,846 Japanese characters fail every buffered, split or multi-source export with `TQL-LD-2855`, naming no column. | medium-high | P2 |
| N1 | Under a base-path prefix (every `tesseraql dev` / `host` stack) the status JSON's `fileUrl` is unprefixed and the card's Download button double-prefixed — both 404. | high within its class | P8 |

RUN by three independent harnesses for S1 (in-process, `host` + gateway + cron, and the
adjudicator's own); the fix was BUILT and measured before this record was written:
`s-final/s1-shipped.log` vs `s1-variant.log` — 500 → 200, FAILED → COMPLETED with the file
delivered, sweep 2 → 2 to 2 → 0.

---

## The decisions

Every decision was taken by the user on 2026-09-13, as recommended; the alternatives and their
costs are in the measurement record §3. Numbering follows it.

1. **S1 ships first, in this slice, by parsing the URI** — no migration, no new column. The
   keyed stores mint `<scheme>:<key>` and look the key up; the file store resolves the URI and
   never reads the id. One helper, two call sites.
2. **The ZIP lever is `entry.setLastModifiedTime(FileTime.from(1980-01-01T00:00:00Z))` on every
   entry** (P1). The JDK then writes the extended-timestamp field into both records, unzip 6.00
   honours the UTF-8 flag, and the bundle is byte-stable. `setTime(long)` with any in-range time
   writes NO extra field (RUN) and is not an option.
3. **Zero rows: rules 1 and 2 in one pull request** (P5) — declared `columns:` printed before the
   row loop, and derived names carried from `ResultSetMetaData` into the codec and a zero-row spool.
   A wire change: a zero-row download grows from 0 to N bytes.
4. **32,767 characters: one rule for the workbook** (P4) — grid and placement refuse with a code
   naming the column and the row; report mode fails through `PoiExceptionThrower` with the cell.
   **4b** (added in review): the worksheet's row (1,048,576) and column (16,384) limits join the
   same rule. fastexcel throws a MESSAGE-LESS `IllegalArgumentException` at either limit (RUN at
   the library boundary), which the async arm records as a NULL reason — item 8's worst shape;
   POI names both. The row limit is reachable (the grid is uncapped); the column limit is not in
   practice (PostgreSQL's table limit is 1,600 columns) and is refused for completeness.
5. **The async reason on the wire is a `code` plus the framework's own sentence** (P8), never the
   raw `exitMessage` (driver text, SQL fragments and an absolute app-home path, readable by anyone
   holding any transfer id through the least-secured route — N2). N1's dead links ship regardless.
6. **A print template renders in the export's locale, or `Locale.ENGLISH` when none is declared,
   folding an empty-language tag (`und`) to ENGLISH too** (P6); the render moves inside
   `PdfFileCodec.write`'s try so a template error is the codec's own `TQL-LD-2831` on every arm.
7. **`push.as:` carrying `{key}` is a lint ERROR** (P7), with the second site (`{key}` in an export
   `filename:` without `splitBy:`) and the unresolved-root arm; `{steps.<id>.filename}` is the
   documented spelling.
8. **Item 3's lint rule and any refusal are F82 slice 2's**; P7 ships one `System.Logger`
   WARNING at the `put` site, last-wins kept.
9. **The `executeQuery` and `after:` failures are wrapped in the existing `TQL-LD-2810`** (P3).
10. **A blank `format: ""` is refused at the predicate on every site** (P7); `RouteCompiler:1406`
    stays untouched for F82 slice 2.
11. **Item 10's code is a new excel-owned LD code**, 2831's sibling (P4).
12. **N2 is filed to the edge/security line**, decided before P8's reason half.
13. **The truncation bound is 2000 UTF-16 units** with the Oracle byte-semantics caveat
    disclosed (P3).
14. **Nine pull requests**, not eight.
15. **`SplitExport.safe`'s lower-casing past 100 units is dropped** (P1).

### The nine, in build order

| # | pull request | items | modules |
|---|---|---|---|
| P0 | `temp.store: db\|blob` — a download, a push and the sweep address the spool by its id | S1, S2, the open-before-claim order | operations, runtime, cli |
| P1 | the split bundle | 1, 2 (cut), 6 (name) | core `SplitExport` |
| P2 | the spool: nothing left behind, no 65,535-byte ceiling | 7, 2855 | core `SpooledRows`, operations |
| P3 | what a failed export says: logged, recorded within its column, coded the same on every arm | 8 (record), 2 (arms), the 2810 wraps | core `ExportWrite`, pipeline, operations |
| P4 | the Excel codec refuses what it cannot write and names its template | 4, 4b, 10 | excel |
| P5 | a tabular export writes its header when the names are known | 5 | operations, excel, core, pipeline |
| P6 | a print template renders in the export's locale | 9 | pdf, studio-runtime, yaml |
| P7 | the declaration path says what the runtime will do | 11, 12, 6 (lint), 3 (WARN) | yaml, core |
| P8 | the card and the status JSON under a prefix — and the reason | N1, 8 (wire) | compiler, core, yaml, ops-ui |

The hard chain is P0 → P2 → P3 → {P4, P5}: P2 and P3 edit the same two catch blocks in
`JdbcFileTransferService`, P4's integration test needs a 100,000-character value the ceiling would
refuse, and P5's rule 2 edits `SpooledRows.drain` and `ExportWrite.write` again. P1, P6, P7 and
P8's link half share no files with the chain. One pull request was refused: eight modules, three
wire or record changes and seven user decisions in one revert unit, with the HIGH mechanical fix
hostage to any reversed decision.

---

## P0 — `temp.store: db|blob`: a download, a push and the sweep address the spool by its id

### What was wrong

`JdbcTempStore` and `BlobTempStore` mint a reference whose id is the key they store under and
whose URI is `tql-temp-db:<id>` / `tql-temp-blob:<key>`; `openInput` and `delete` look the
**id** up (`where spool_id = ?`, `blobRef(ref.id())`). `FileTempStore` resolves the **URI** and
ignores the id. A transfer row stores the URI only. `JdbcFileTransferService.download():478` and
`expireTransfersOlderThan():508` rebuilt the reference as `new SpoolRef(transferId, …, URI)` — a
shape that works on the file store alone. The import side knew the trap (`spoolOf`
stores both halves and says why); the export side did not.

Measured (RUN, `m-drain-spool-leak.md` §2, `a-drain-spool-leak.md` §2-4, `s-final/`):

- `GET …/{transferId}/file` on a COMPLETED `file-export` under `store: db` → **500
  `TQL-ROUTE-5000`**, log `IOException: Spool <transferId> not found`; the ops console download the
  same. The synchronous route works (its reference comes from `writer.toRef()`), which is why the
  only db-store integration test was green.
- `PushStepRunner:42` reads through `download()` → **every export-then-push job FAILED**, 7 of 7
  cron firings, nothing delivered.
- The retention sweep deleted nothing (`tql_temp_spool` 21 → 21) while nulling `spool_uri` on
  every row it visited — the pointer gone, the bytes kept, forever.
- `download()` called `claimFirstDownload` (marks `downloaded_at`, fires `after: timing:
  afterDownload`) at `:466`, BEFORE `openInput` at `:481`: the 500 above left the transfer
  recorded as downloaded (`downloaded: false` → 500 → `downloaded: true`).
- `JobCommand.wire:430` built `new FileTempStore(scratch)` unconditionally: under a db-store app a
  CLI-run step recorded `spool_uri = file:///…` in the shared table (S2).

### The change

- **`JdbcFileTransferService.exportSpool(transferId, spoolUri, rows)`** — the export side's twin of
  `spoolOf`: the id is the URI's scheme-specific part for every scheme but `file`, where it stays
  the transfer id (the file store never reads it). Used by `download()` and the sweep. One rule,
  no store enumeration: a keyed store mints `<scheme>:<key>` by contract.
- **`download()` opens the bytes first and claims second.** The first-download claim and the
  after-download SQL follow a successful `openInput`; a claim that throws closes the stream it
  would have abandoned. A download that cannot open its bytes still answers 500 — what it says is
  P3's and P8's.
- **`TempStores`** (tesseraql-runtime): the one reading of `tesseraql.temp.store`, lifted from
  `TesseraqlRuntime`'s boot; `TesseraqlRuntime` and `JobCommand.wire` both build their store
  through it. `TempStores.scratch` is the shared node-local scratch directory, which the runtime
  also hands to the request-body upload spool (#1149). `TQL-YAML-1024` (an unknown store name) is
  raised from `TempStores` now; the reference page's source link moves with it.

### The guards, red before the fix

`SharedTempStoreIntegrationTest` (tesseraql-runtime, Testcontainers PostgreSQL, the existing
`store: db` app gains a `file-export` route and an export-then-push job) and
`JobCommandIntegrationTest` (tesseraql-cli):

| guard | asserts | HEAD `5fa510c8b` |
|---|---|---|
| `anAsyncExportUnderTheDatabaseStoreDownloads` | POST → 202 → COMPLETED → `GET /file` 200 `text/csv` with the rows; `downloaded: true` after | 500 |
| `anExportAndPushJobUnderTheDatabaseStoreDelivers` | `runJob("orders.deliver")` COMPLETED; `outbox/partner/orders.csv` exists with the rows | FAILED `Spool … not found` |
| `theSweepReclaimsTheSpoolRowsUnderTheDatabaseStore` | the transfer's spool row exists; `expireTransfersOlderThan(now + 60 s)` ≥ 1; the row is gone AND `spool_uri` is null | row still there (1) |
| `aDownloadThatFailsIsNotRecordedAsDelivered` | the spool row deleted from under a COMPLETED transfer; `GET /file` ≠ 200; `downloaded` stays false | true |
| `theCliJobRunHonoursTheConfiguredTempStore` | `tesseraql.temp.store: db` in the scaffold; `job run` exits 0; the step's `spool_uri` starts `tql-temp-db:` | `file:///…` |

**Bracket** (`work/export-hygiene-measurement/p0/bracket/`, surefire XML per column, the
module jars rebuilt and reinstalled before each column): HEAD 5 red as above; the fix 7/7 + 1/1
green; **V-noreorder** (the claim back before the open) — exactly `aDownloadThatFails…` red;
**V-nosweep** (the sweep still by the transfer id) — exactly `theSweepReclaims…` red;
**V-noS2** (the CLI still builds the file store) — exactly the CLI guard red.

The hazards these guards were built against (measurement §5 #47-51): a test on the file store or
the sync route is green today; `status == COMPLETED` is true today (assert the download's status
and bytes); a count driven through the CLI runner writes a `file:///` URI and downloads by accident
on the same host; the sweep is opt-in (`retentionDays`), and the file-store sweep DOES delete
(the positive control) — "the sweep runs" is green; the open-before-claim order is red only when
the spool is removed from under a COMPLETED transfer.

### What this breaks

Nothing on the file store: `exportSpool` hands it exactly today's reference. Under `db` or `blob`
the documented behaviour starts working; a sweep now frees bytes it used to keep. `tesseraql job
run` under a db- or blob-store app creates `tql_temp_spool` on first use (the served runtime did
already) and writes there; a deployment that relied on the CLI's files under `work/tmp/tesseraql`
has none from export steps any more.

### Filed, not fixed (from P0's measurement)

- **`tesseraql job run` before the first runtime boot poisons the framework schema**: the CLI's
  `ensureSchema` creates `tql_job_execution` with the `triggered_by` column, and the next runtime
  boot fails Flyway `V3__job_execution_actor.sql` (RUN twice on two fresh databases; the database
  never boots). HIGH, the documented external-scheduler flow — the batch/CLI line, its own filing.
- A failed download's wire shape (500 `TQL-ROUTE-5000`) — P3 records the reason, P8 projects it.
- `temp.store: blob` is unmeasured end to end (no object store here); `BlobTempStore.blobRef`
  keys on `ref.id()` exactly as `JdbcTempStore` does (READ `:76-79`), so the helper covers it by
  construction.

---

## P1 — the split bundle

### What was wrong

Three defects in one file, `SplitExport` (dependency-free core):

- **Item 1.** `write()` put entries with no extra field. Info-ZIP `unzip` 6.00 (Debian 6.0-28,
  `fileio.c do_string`) reads the UTF-8 name flag inside its `EXTRA_FIELD` case and returns before
  that case when the field has length 0, so an entry with no extra field is converted as OEM
  whatever the flag says: `受注-東京.csv` unpacked as garbage under a UTF-8 locale. 7z, libarchive,
  Python and Java read the name correctly. The host byte the filing named is neither reachable
  through `ZipOutputStream` (`versionMadeBy` keys on the package-private external attributes) nor
  needed. Two identical exports also differed in their bytes — the DOS "now" of each entry.
- **Item 2, the cut.** `safe()` cut a key over 100 UTF-16 units at `substring(0, 100)`; a key with
  an odd prefix before astral letters (`X` + `𠮷`×50) leaves a lone surrogate at unit 99, which
  the ZIP name encoder refuses (`malformed input off : 104`) after the query ran. The over-length
  branch alone lower-cased (since #714, no rationale, no test), so `A`×101 and `a`×101 collided
  as `TQL-LD-2857` while `A`×100 and `a`×100 did not.
- **Item 6.** `zipName()` dropped `{key}`, cut at the last dot, and stripped a trailing separator
  run only: `{key}.users.csv` bundled as the dot-file `.users.zip` (RUN delivered as such into a
  partner drop), `users-{key}-daily.csv` as `users--daily.zip`, `users-{key}.tar.gz` as
  `users-.tar.zip`. Every documented example is `{key}`-last with a single extension and was right.

### The change

- `ENTRY_TIME` = `1980-01-01T00:00:00Z`; `write()` stamps every entry with
  `setLastModifiedTime(ENTRY_TIME)`, which makes the JDK write the 0x5455 extended-timestamp field
  into the local header and the central directory. **Decision 2.** `setTime(long)` with any time
  in the DOS range writes no field at all (RUN, `s-final/zip/lever.log`: `setTime1980: CEN extra=0`).
- `safe()` cuts at unit 99 when units 99 and 100 are a surrogate pair, and keeps the case.
  **Decision 15.**
- `zipName()` takes the stem (the name before its last dot) and collapses `[-_.]*{key}[-_.]*`:
  to nothing at either end of the stem, to the run's first separator in the middle; the trailing
  strip stays for a run the collapse leaves at the end. `users-{key}.tar.gz` bundles as
  `users-tar.zip` — `.tar.gz` is not a real split declaration (csv bytes in an entry named
  `.tar.gz`), and P7's extension-versus-format warning says so.

### The guards, red before the fix

`SplitExportTest` and `SplitExportZipNameTest` (core unit tests, no container):

| guard | asserts | HEAD `abcbb8ff6` |
|---|---|---|
| `everyEntryCarriesTheExtendedTimestampInBothRecords` | two Japanese entries; the extra field starts `0x55 0x54` in the local header (`ZipInputStream`) AND the central directory (`ZipFile`); every stamp is `1980-01-01T00:00:00Z` | `extra == null` |
| `aLongKeyIsCutOnACodePointBoundaryAndKeepsItsCase` | `X`+`𠮷`×50 → 99 units ending in a full pair; `A`×101 → `A`×100 | `x…` lower-cased, 100 units |
| `aKeyEndingInAnAstralLetterPastTheBoundIsWritten` | the entry is written, named `team-X𠮷…𠮷.txt` | `IllegalArgumentException: malformed input off : 104` |
| `twoLongKeysDifferingInCaseAreTwoDocuments` | `A`×101 and `a`×101 → two entries | `TQL-LD-2857` |
| `thePlaceholderCollapsesWithItsSeparatorsAtAnyPosition` | nine shapes, including `{key}-users.csv` → `users.zip`, `{key}.users.csv` → `users.zip`, `users-{key}-daily.csv` → `users-daily.zip`, `report.{key}.2026-09.csv` → `report.2026-09.zip`, `.hidden-{key}.csv` → `.hidden.zip` (unchanged) | `-users.zip` |

**Bracket** (`work/export-hygiene-measurement/p1/bracket/`): HEAD 5 red; the fix 12/12 + 3/3
green; **V-settime** (`setTime(ENTRY_TIME.toMillis())`) — exactly the extra-field guard red, the
stamp assertion alone would have been green (hazard 4); **V-lowercase** (the cut kept, the
lower-casing back) — the three case guards red; **V-trailing** (HEAD's `zipName`) — exactly the
name table red. The bytes are asserted, never an extracted name (`java.util.zip` reads the name
correctly either way; an `unzip` shell-out is green where the binary is absent and RED on a correct
tree under the `C` locale, where the fixed bundle prints reversible `#Uxxxx` escapes).

### What this breaks

Every entry's modification time reads 1980-01-01 in every reader. A bundle name changes only for
a `{key}` that is not immediately before a single extension — no shipped or documented declaration.
The three pinned `{key}`-last shapes and the placeholder-only shapes are unchanged.

### Filed, not fixed (from P1's measurement)

- The case-insensitive-filesystem collision (`Abc` and `abc` are two entries and one file on
  Windows or macOS; the 2857 check is case-sensitive) — filed as it was.
- The emoji fold (`😀` → `_`, so two emoji keys collide) — `safe()`'s class is `\p{L}\p{N}`; a
  design question for the split-export line, not this slice.
- Whether `zipName` should cut at the codec's extension instead of the last dot — moot once P7's
  warning names a mismatched extension.

---

## P2 — the spool: nothing left behind, no 65,535-byte ceiling

### What was wrong

- **7(a), the drain spool.** `SpooledRows.drain` wrote through a `try (writer)`. When the source
  refused part-way — the row cap (`TQL-LD-2850`), an unrepresentable value (2853), a shape
  change (2854), a database error surfacing in `hasNext()` — the try-with-resources closed the
  writer, which on `JdbcTempStore`/`BlobTempStore` INSERTS the partial spool, and then nothing
  held the reference. One orphan per failed run on every surface and both stores; the retention
  sweep walks rows that carry a `spool_uri` and never sees one. The sync route leaked on every
  buffered codec, any `splitBy:`, and any NAMED source — a csv route whose named source failed
  leaked too (`namedResult` calls `drain`; a fix in `ExportWrite.write` would have missed it).
- **7(b), the writer spool.** `runExport` (async) and `exportInline` (job) created the document
  writer inside the try; on a failure the writer was closed and its reference recorded nowhere —
  0 B for a buffered codec, the partial document for a streaming one (46,592 B measured). The
  route arm (`SqlStep.export`) already deleted its writer's spool.
- **The 2855 ceiling.** `SpooledRows.write` spelt a `String` with `DataOutputStream.writeUTF`,
  whose ceiling is 65,535 bytes of modified UTF-8 — three per BMP non-ASCII character, six per
  astral one: 21,846 Japanese characters failed with `TQL-LD-2855 "Could not spool rows: encoded
  string (いいいいいいいい...) too long"` on everything that spools, the named sources of a csv
  export included, naming no column and leaking a header-only spool per breach (7's fourth
  trigger). Since #708. Boundary pinned: 65,535 ASCII / 21,845 `あ` / 10,922 `𠮷` passed.

### The change

- `SpooledRows.drain` catches `IOException`, `RuntimeException` and `Error`, deletes
  `writer.toRef()` (a delete that fails rides the failure as a suppressed exception; `toRef()`
  throws when `close()` itself failed on a staging store — nothing to release then) and rethrows.
- `runExport` and `exportInline` hoist the writer to method scope; the outer catch deletes its
  spool BEFORE `failExecution`, unless the spool was recorded (a `spoolRecorded` flag set after
  `recordSpool` / `recordSpoolAndComplete` — a throw after the record must never delete a
  COMPLETED export's bytes).
- `SpooledRows` gains the type tag `LONG_STRING = 19`: a `String` longer than 21,845 UTF-16 units
  (the longest `writeUTF` is certain to accept) is written as a length-prefixed UTF-8 body; the
  reader understands both tags. A new tag rather than a new `STRING` encoding: under `temp.store:
  db|blob` a spool written on one node is read on another, so a spool written before this change
  still reads. A node running the previous version cannot read a `LONG_STRING` spool — pre-1.0,
  recorded here, no upgrade steps.

### The guards, red before the fix

| guard | asserts | HEAD `5cc940e91` |
|---|---|---|
| `SpooledRowsTest.aDrainThatFailsMidWayLeavesNoSpoolBehind` | a source that throws after two rows, an unrepresentable value, a shape change — each leaves 0 files; `theRowsCanBeWalkedMoreThanOnce` gains the control (1 file alive, 0 after close) | 1 |
| `SpooledRowsTest.aTextPastTheModifiedUtf8CeilingRoundTrips` | 65,535 and 65,536 ASCII, 21,846 `い`, 10,923 `𠮷` round-trip through the READER | `2855 … too long: 65536 bytes` |
| `ExportStreamingProfileIntegrationTest.aFailedExportLeavesNoSpoolOnEitherStore` | a buffered codec under `ExportRowCap(2)` over three rows, through `exportInline` (job arm) AND `startExport` (async arm), on `FileTempStore` (files) AND `JdbcTempStore` (rows): the count is unchanged; 2850 raised; the async execution FAILED | 2 files (drain + writer) |
| `SharedTempStoreIntegrationTest.aNamedSourcePastTheUtf8CeilingExportsAndLeavesNoOrphan` | a csv route with a `note` source of `repeat('い', 21846)` under `store: db` answers 200 with the rows and `tql_temp_spool` grows by 0 | 500, +1 |

**Bracket** (`work/export-hygiene-measurement/p2/bracket/`, the core and operations jars
reinstalled before each column): the fix 7/7 + 4/4 + 8/8 green; **V-nodrain** — the drain guard
and the IT red (1 file: the drain spool); **V-nowriter** — the IT red at the job arm (1 file: the
writer spool), core green; **V-writeronly** (async fixed, job not) — the IT red at the job arm;
**V-asynconly** (job fixed, async not) — the IT red at the ASYNC arm; **V-writerside** (the
`LONG_STRING` written, the reader never taught) — exactly the round-trip guard red (`Unknown
spool type tag 19`), every count-based guard green — hazard 45 made real.

Disclosed: the named-source route guard is a CEILING guard on the sync arm — its 21,846-character
value is drained and, on csv, never read back, so V-writerside leaves it green; the sync arm's
named-source LEAK is covered by the core guard (`namedResult` calls `drain`), not by a route test.

### What this breaks

Nothing on the success path. A spool written by this version may hold a `LONG_STRING` tag a node
on the previous version cannot read (`TQL-LD-2855 Unknown spool type tag 19`) — a mixed-version
`db|blob` deployment during a rolling upgrade, pre-1.0.

### Filed, not fixed (from P2's measurement)

- The header names and the temporal/decimal `toString()`s still go through `writeUTF` — a
  column NAME over 65,535 bytes is not a shape this framework meets.
- 2855's message still quotes the value's first and last eight characters (`exit_message` leaks a
  value fragment) — the error-hygiene line; P3 may drop it in passing.
- A failure BEFORE `createWriter` (a named-source cap breach in `composedValues`) reaches the
  drain half only — by construction there is no writer spool to release.

---

## P3 — what a failed export says: logged, recorded within its column, coded the same on every arm

### What was wrong

Item 8 (the async FAILED reason) and item 2's arm half, plus decision 9's two SQL sites:

- **No code on the async and job arms.** 5b's `TQL-LD-2802` wrap lived in `SqlStep` (the route);
  `runExport` and `exportInline` recorded `ex.getMessage()` — the raw JDK, driver, POI or
  Thymeleaf text (`Unsupported field: YearOfEra`, `malformed input off : 105`, `The maximum length
  of cell contents (text) is 32767 characters`), and NULL for a message-less exception. A
  `TqlException` carried its code as a text prefix; five of nine measured failure shapes did.
- **The 2000-character cliff.** `exit_message` and `error_message` are `varchar(2000)`;
  `bindFinish`, `failStep` and `markReaped` bound the message raw (only `recordSkip` cut it). A
  longer reason failed the UPDATE: the async arm surfaced `TQL-BATCH-5001 … value too long` in
  place of the export's failure, and on the job arm the step's transfer execution stayed RUNNING
  until the reaper finished it as a FALSE `TQL-BATCH-4212 "owner stopped reporting … abandoned"` —
  an infrastructure incident that never happened.
- **No stack.** `runExport`'s and `guarded()`'s `LOG.warn` carried the message alone — one WARN
  line, under the submitting request's span, the only trace of any failure.
- **Two raw-text SQL sites** (decision 9): a database error at `executeQuery` (on PostgreSQL
  anything the first fetch batch evaluates — a bad expression, a missing relation) and one in the
  `after:` statement recorded the driver's text, while the same error one batch later was already
  `TQL-LD-2810` through the row iterator.

### The change

- **The 2802 lift.** `ExportWrite.write` (core, the one write every surface shares) wraps its
  dispatch: a `TqlException` passes through as itself, any other `IOException` or
  `RuntimeException` becomes `TQL-LD-2802 "Writing the <format> document failed after the query
  ran: <cause>" [<filename>]`. `DOCUMENT_WRITE_FAILED` moves to `ExportWrite`; `SqlStep` loses its
  `DocumentWriteFailure` marker class and `documentError` — its `catch (TqlException) { throw }`
  passes the lifted code through, so the route is byte-identical and never wraps twice. The
  reference page's 2802 row moves its source to `ExportWrite.java`. Built and measured by the
  adjudicator before this record existed (`s-final/lift-*.raw`).
- `JobRepository.withinColumn` (2000 UTF-16 units) in `bindFinish`, `failStep` and `markReaped`,
  as `recordSkip` always did. **Decision 13:** Oracle's `varchar2(2000)` counts bytes under its
  default length semantics, so a long non-ASCII reason can still exceed it there — disclosed in
  the Javadoc and the CHANGELOG, a dialect-suite check post-merge.
- `runExport`'s and `guarded()`'s `LOG.warn` pass the throwable — the stack rides the line.
- `executeExtraction` and `executeFollowUp` in `runExport` wrap the two `SQLException` sites in
  the existing 2810 (`Export query failed: …` / `Export follow-up statement failed: …`). The job
  arm already codes everything as `2810: Export step failed: …` and is left alone (a second wrap
  there would read `2810: … 2810: …`).

### The guards, red before the fix

`ExportFailureRecordIntegrationTest` (tesseraql-runtime; the direct-service harness on
Testcontainers PostgreSQL) and one assertion added to the route arm's existing test:

| guard | asserts | HEAD `191de9633` |
|---|---|---|
| `aFailedAsyncExportRecordsItsCodeAndLogsItsStack` | a codec throwing `IllegalStateException("the codec broke")` through `startExport`: FAILED, `exit_message` starts `TQL-LD-2802: Writing the broken document failed after the query ran: the codec broke` and contains `[items.csv]`; the captured stderr has the WARN line AND `\tat ` frames naming the codec's class | `the codec broke`; no frames |
| `aFailedJobArmExportRecordsItsCodeWithinTheColumn` | a 2,100-character codec message through `exportInline`: the thrown 2810 wraps 2802; the transfer execution is FAILED with a 2,000-character `exit_message` starting `TQL-LD-2802` | `TQL-BATCH-5001 … value too long`, RUNNING |
| `aStepFailureIsRecordedWithinItsColumn` | `failStep` and `failExecution` with 2,100 characters record 2,000 | `value too long for type character varying(2000)` |
| `aDatabaseErrorAtTheStartOfTheExtractionIsCoded` | `select 1 / 0` through `startExport`: `exit_message` starts `TQL-LD-2810: Export query failed:` and names the division | `ERROR: division by zero` |
| `aFailingFollowUpStatementIsCoded` | an `after:` statement on a missing table: `TQL-LD-2810: Export follow-up statement failed:` naming the table | `ERROR: relation "no_such_table" does not exist` |
| `ExportRequestFormatsIntegrationTest.aFailureWhileWritingTheDocumentIsFiledUnderItsOwnCode` (+1 assertion) | the route's ERROR line never reads `failed after the query ran: TQL-LD-2802` — raised once, where the write happens | green (the regression guard) |

**Bracket** (`work/export-hygiene-measurement/p3/bracket/`, core + operations + pipeline jars
reinstalled per column): HEAD 5 red; the fix 5/5 + the route arm's two classes green;
**V-doublewrap** (`SqlStep` wraps a second time) — exactly the route assertion red, the five arm
guards green; **V-bindfinish** (`withinColumn` in `bindFinish` only) — exactly the step guard red
(hazard 53); **V-nosqlwraps** — exactly the two SQL-site guards red; **V-nostack** (the message
without the throwable) — exactly the stack assertion red (hazard 59).

### What this breaks

The recorded `exit_message` of a failed async export or job step changes shape: `TQL-LD-2802: …
[file]` for a write failure, `TQL-LD-2810: Export query failed: …` for a first-fetch SQL error, a
2,000-character cut for a long reason; a job step's execution reads `TQL-LD-2810: Export step
failed: TQL-LD-2802: …` (one level added, said here). Nothing on the wire changes — the status JSON
still carries no reason until P8. No test asserted the raw shapes.

### Filed, not fixed (from P3's measurement)

- Studio's export preview writes (`StudioSupport:483/515`) call the codec directly, outside
  `ExportWrite` — the Studio backlog.
- The transfer span invisible to the ops traces API (no `app` attribute) — the ops line.
- 2831's absolute app-home path and the 2853/2855 value snippets inside a recorded reason — the
  error-hygiene line.

---

## P4 — the Excel codec refuses what it cannot write and names its template

### What was wrong

Items 4, 4b and 10, all inside `JxlsFileCodec`:

- **The 32,767-character cell (item 4).** The grid (fastexcel) wrote a longer text — LibreOffice,
  POI, openpyxl and fastexcel-reader read it, Excel repairs the file on open (CITED; no Excel on
  this host). Placement threw POI's `IllegalArgumentException("The maximum length of cell contents
  (text) is 32767 characters")` naming no column — 2802 on the route since 5b, raw on the async
  and job arms until P3. **A jxls report COMPLETED with the cell blank** on every arm:
  `JxlsPoiTemplateFillerBuilder` installs `PoiExceptionLogger`, which logs the cell exception and
  moves on; the only trace was jxls's own `JXLS [ERROR]` on stderr.
- **The worksheet's dimensions (4b).** fastexcel's `Worksheet.cell(row, col)` throws a
  MESSAGE-LESS `IllegalArgumentException` at row 1,048,576 or column 16,384 (RUN at the library
  boundary): `exit_message` NULL on the async arm — item 8's worst shape. The row limit is
  reachable (the grid is a streaming codec and uncapped); the column limit is not in practice
  (PostgreSQL's table limit is 1,600 columns).
- **A template present but unusable (item 10's live half).** `write()` chose the mode by
  `spec.template() != null && Files.isRegularFile(template)`: a template deleted after boot fell
  through to the GRID, whose `model.rows()` on a repeatable model raised `TQL-LD-2856` — the
  row-set sentence, naming neither the file nor the reason; an empty or text file passed
  `isRegularFile` and failed inside POI with the OUTPUT filename as source. Lint and boot judge
  existence when the app loads (#1306), so the codec is the only place that sees a file deleted,
  emptied or replaced afterwards.

### The change

- **`TQL-LD-2836` `WORKBOOK_LIMIT`** (decision 4, 4b): `requireCellText` in the grid's
  `writeValue` and placement's cell loop — `Column 'body' at data row 2 holds 32,768 characters; a
  workbook cell holds at most 32,767 - shorten the value in the query, or export it as csv`;
  `requireRow` before every data row in both modes — `The export reached data row 1,048,576 and a
  worksheet holds at most 1,048,576 rows including the header - split the export with splitBy:,
  or export it as csv`; `requireColumns` once per export. Report mode installs
  `.withLogger(new PoiExceptionThrower())` and wraps the resulting `JxlsException` (which names the
  cell and the expression, `B2` / `${r.body}`) in the same code.
- **`TQL-LD-2837` `TEMPLATE_UNUSABLE`** (decision 11, 2831's excel-owned sibling): `write()` no
  longer falls through — a declared template is used or refused. `requireWorkbook` refuses a
  directory, a missing file, an empty file, and a file whose first bytes are neither the OOXML
  (`PK\x03\x04`) nor the OLE2 signature, each `The workbook template <path> <reason> - restore the
  file the export declares, or fix template:`. A signature check rather than a full POI parse:
  cheap, dependency-free, and it catches every measured shape.
- Both codes join `StatusMappingLedgerTest`'s recorded set (raised after the query ran; the
  route answers 500 through 2802, the other arms as a failed transfer) and `docs/file-transfers.md`'s
  code table; `docs/reference-error-codes.md` regenerated.

### The guards, red before the fix

`JxlsFileCodecLimitsTest` (tesseraql-excel, POI-authored fixtures — an openpyxl-authored
template holds `inlineStr` cells jxls cannot copy and a control would be red for the wrong reason)
and one runtime IT:

| guard | asserts | HEAD `b8d775ca1` |
|---|---|---|
| `theGridRefusesATextPastTheCellLimitNamingColumnAndRow` | 32,768 chars in row 2 → 2836 naming `'body'`, `row 2`, `32,767` | writes |
| `theGridWritesATextAtTheCellLimit` | 32,767 chars round-trip (the control) | green |
| `placementRefusesATextPastTheCellLimitNamingColumnAndRow` | 2836 naming `'body'`, `row 2` | raw POI `IllegalArgumentException` |
| `aReportRefusesATextPastTheCellLimitNamingTheCell` | a 10-character control renders; 32,768 → 2836 naming `B2` | COMPLETES, cell blank |
| `theGridRefusesTheRowPastTheWorksheetLimitNamingTheLimit` | 1,048,577 synthetic rows → 2836 naming `1,048,576` and `splitBy` | message-less `IllegalArgumentException` |
| `theGridRefusesMoreColumnsThanAWorksheetHolds` | 16,385 columns → 2836 naming `16,384` | message-less `IllegalArgumentException` |
| `aTemplateThatIsNotAWorkbookIsRefusedByName` | missing / directory / empty / text × report / placement → 2837 naming the file | `TQL-LD-2856` (report), `NoSuchFileException` etc. |
| `ExcelTransferIntegrationTest.aTemplateDeletedAfterBootIsRefusedByNameOnEveryArm` | the fragile twins (a `file-export`, a `query-export`, a job step, each with its own template copy): the sync control 200; the template deleted BETWEEN boot and request → sync 500 `TQL-LD-2837`, async FAILED with `exit_message` naming 2837 and `fragile-frame.xlsx`, `runJob` FAILED the same | sync `TQL-LD-2856` |

**Bracket** (`work/export-hygiene-measurement/p4/bracket/`, the excel jar reinstalled per column):
HEAD 6 + 1 red; the fix 7/7 + 14/14 + 4/4 green; **V-gridwrites** — exactly the grid text guard
red; **V-nothrower** — exactly the report guard red (the silent blank returns); **V-fallthrough**
(`requireWorkbook` a no-op) — the template guard red in both classes (the IT reads `TQL-LD-2802`:
P3's lift now dresses the fall-through's `NoSuchFileException`, which is why the by-name code
matters); **V-nodimensions** — exactly the two dimension guards red.

### What this breaks

A grid export that COMPLETED with a text over 32,767 characters — readable in LibreOffice — now
fails with 2836 (decision 4: one rule for the workbook). A jxls report over an openpyxl-authored
template with static `inlineStr` cells turns from silent blanks into a hard failure (right, and
said in the CHANGELOG). A template deleted after boot answers `TQL-LD-2837` naming the file where
it answered `TQL-LD-2856`; `TQL-LD-2856` itself is unchanged for its own case (a repeatable model
walked as a stream).

### Filed, not fixed (from P4's measurement)

- The `RouteReloader` fingerprint hole: a template in a subdirectory (`tpl/report.xlsx`) or under
  `../shared/` is outside `digestDirectory` (immediate children), so `--watch` and Studio Apply see
  neither its deletion nor its restoration; a stub stays until a manual reload — a reloader filing.
- The Excel codec accepts `..` in `template:` (the pdf codec confines to the app home) — the same
  filing.
- Microsoft Excel's own behaviour on a >32,767-character grid cell — unmeasured (no Excel here);
  the refusal does not depend on it.

---

## P5 — a tabular export writes its header when the names are known

### What was wrong

Item 5, wider than filed: csv wrote 0 bytes (3 with `bom: true`) and the Excel grid a cell-less
workbook at zero rows — **with declared `columns:` as well as derived** (the declared list was
held and discarded, the header printed inside the row loop) — on the sync route, the async route
and the job step under both runners. The pdf grid already printed a declared header and nothing
for derived names. `ResultSetMetaData` labels are available before the first row on every
`ResultSetRows` surface (RUN `MetaProbe`) and were never plumbed: `ResultSetRows` read them into
`labels` and kept them private; `SpooledRows.drain(zero rows).columns()` was `[]`. Reachable
today with the shipped `users/export` and `shipments/export` (derived) and `daily-price-report`
(declared). Consumers: pandas `EmptyDataError`, PostgreSQL `COPY … HEADER MATCH` (error), DuckDB
(fabricates `column0`), this framework's own `file-import` (400 `TQL-LD-2820`). Studio's
data-browser CSV already wrote the header at zero rows under a comment claiming byte-for-byte
consistency with query-export.

### The change (decision 3: rules 1 and 2 in one pull request)

- **`NamedRows`** (core): a row source that knows its column names before the first row.
  `ResultSetRows` implements it from its metadata labels; `SpooledRows` from its header.
  `EnrichingRows` does NOT — enrichment adds keys no metadata can know, so an enriched export
  wanting a stable header on the empty day declares `columns:` (documented in `file-transfers.md`).
- **`ExportModel.knownColumns()`**: the source's names, or an empty list. `ColumnMapping.deriveIfAbsent(columns, names)`
  overload.
- **Rule 1 + rule 2 in the codecs**: `CsvFileCodec.write` and the Excel grid derive the columns
  from `knownColumns()` and write the header BEFORE the row loop when any column is known; the
  in-loop derivation from the first row stays for a source that knows nothing (a plain list), which
  still writes nothing at zero rows. The pdf grid derives from `knownColumns()` too.
- **The spool**: `SpooledRows.drain` with no row takes the columns from a `NamedRows` source
  before writing its header, so a buffered export (pdf, placement, report, a `splitBy:`) sees the
  names through the spool.

### The guards, red before the fix (HEAD `4cc419ab5` + the empty `NamedRows` interface, so the
tests compile)

| guard | asserts | HEAD |
|---|---|---|
| `SpooledRowsTest.aZeroRowDrainKeepsTheSourcesColumnNames` | a `NamedRows` source with no rows → `columns() == [id, name]`, size 0, no file left | `[]` |
| `CsvFileCodecTest.aZeroRowExportWritesItsHeaderWhenTheNamesAreKnown` | declared → `ID,Name\r\n`; a `NamedRows` source → `id,name\r\n`; a plain empty iterator → 0 bytes | 0 bytes |
| `JxlsFileCodecLimitsTest.aZeroRowGridWritesItsHeaderWhenTheNamesAreKnown` | declared and derived → a header row in the workbook | no row |
| `PdfFileCodecTest.aZeroRowGridPrintsTheSourcesColumnNames` | a zero-row spool from a `NamedRows` source → the text carries the names | `Page 1 / 1` alone |
| `ExportByteOrderMarkIntegrationTest.anEmptyMarkedExportIsTheMarkAndItsHeader` (G26, reasserted) | `/api/items/empty` → exactly `EF BB BF` + `Name\r\n` | exactly the mark |
| `ExportByteOrderMarkIntegrationTest.anEmptyExportWithDerivedColumnsCarriesTheQuerysNames` | a route with no `columns:` over an empty result → `name,qty\r\n` | 0 bytes |
| `SharedTempStoreIntegrationTest.aZeroRowExportCarriesItsHeaderOnTheAsyncAndJobArms` | a `file-export` and an export-then-push job over `where id < 0` → the download and the delivered file read `id,status\r\n` | 0 bytes |

`CsvFileCodecTest.anEmptyExportWithTheMarkIsExactlyTheMark` (G20) stays green by design: no
columns declared and a source that knows none — its Javadoc says so now.

**Bracket** (`work/export-hygiene-measurement/p5/bracket/`, four module jars reinstalled per
column): HEAD 7 red; the fix 8/8 + 16/16 + 8/8 + 9/9 + 6/6 + 9/9 green; **V-rule1only**
(`knownColumns()` answers nothing) — every DERIVED guard red (csv, grid, pdf, the derived route,
the async/job arms) and every declared assertion green — hazard 26 made real; **V-nospool** (the
zero-row drain forgets the names) — exactly the spool guard and the pdf guard red, the streaming
arms green.

### What this breaks

A wire change: a zero-row csv grows from 0 to N bytes, a zero-row grid from a cell-less workbook
to a header row, a marked empty csv from 3 bytes to the mark and its header. A consumer that
tested `length == 0` should read `rowCount`. `docs/download-name-and-bytes.md` D1 ii and G26 are
superseded (amended there); G20 stands. The Studio data-browser CSV's byte-for-byte claim becomes
true at zero rows.

### Filed, not fixed (from P5's measurement)

- `splitBy:` at zero rows stays the documented empty ZIP.
- Placement mode at zero rows returns the template unchanged (the template owns its headings).
- The result-column-types design wants a `ResultSetMetaData` seam on `ResultSetRows` for TYPES;
  `NamedRows` is the names half — build the types half as a sibling accessor, not a second read.

---

## P6 — a print template renders in the export's locale

### What was wrong

Item 9, one half wider than filed. `PdfTemplates.render` / `renderGrid` built `new
Context(Locale.ROOT, model)` whatever the export declared. The declared, request-sourced (5b) or
configured locale reached the `ColumnValues` columns and never the Thymeleaf context: one
document, two locales (`1.234,50` beside `1,234.50`; `05. März 2026` beside `Thu 05. Mar 2026`;
`${#locale}` empty, tag `und`) on the route, the async file-export and the job step (RUN,
md5-identical). A `de-DE` JVM made an UNDECLARED job export byte-identical to the declared one —
the typed columns already followed the JVM default, so `PdfTemplates`' "reproducible" rationale
for ROOT was hollow. **New: ANY message lookup** (`#{key}`, `#messages.msg`, `#messages.msgOrNull`)
threw under ROOT (Thymeleaf 3.1 `StandardMessageResolutionUtils: Locale "" cannot be used`) AFTER
the query ran — route 500 `TQL-LD-2802`, async FAILED with the raw text, job 2810 — because
`PdfFileCodec.write` rendered the template OUTSIDE its try, so a template error was never the
codec's own 2831. Lint is silent (1006 judges existence, never the body). No shipped or
documented template uses a utility or a message expression; both shipped templates are
byte-identical under every fix shape × JVM locale (12/12 md5).

### The change (decision 6, shape (c))

- `PdfTemplates.templateLocale(declared)`: the export's locale when declared, `Locale.ENGLISH`
  otherwise — and for a tag with no language (`und`, what a negotiated `request.locale` becomes
  when the i18n default folds; `export-declarations.md` decision 27), because as ROOT it would
  bring the throw back. `render` and `renderGrid` take the locale; `PdfFileCodec.render` resolves
  it from `spec.locale()` — the value the 5b chain already resolved (literal → source → config),
  null when none.
- The render moves inside `PdfFileCodec.write`'s try: a template that cannot be rendered is
  `TQL-LD-2831 "PDF rendering failed for template '<name>': …"` on every arm (a `TqlException`
  passes through `ExportWrite`'s lift as itself).
- `StudioSupport.renderExportPdf` passes a literal `locale:` / `timezone:` through
  `withFormatting`; a request-sourced value has no request in the preview and stays null.
- `ExportDeclarations`' `TQL-YAML-1005` columns advisory is quiet for `pdf` + `template:` +
  `locale:` — its premise ("reaches only a typed column") is false there; a zone alone still draws
  it.

### The guards, red before the fix

`PdfFileCodecTest` (tesseraql-pdf, `PDFTextStripper`; the template uses a number utility with
`'DEFAULT'` separators, a date utility with day and month names, `${#locale.toLanguageTag()}` and
a `#{greeting}` with `_de` and `_en` property siblings — a template that only echoes `${…}` values
is byte-identical under every shape, hazard 63):

| guard | asserts | HEAD `e877cfb87` |
|---|---|---|
| `aTemplateRendersInTheExportsLocale` | `locale: de-DE` → `1.234,50`, `Donnerstag`, `deutsch`, `de-DE` | `Locale "" cannot be used` |
| `anUndeclaredLocaleRendersInEnglishWhateverTheJvmSays` | no locale under `Locale.setDefault(GERMANY)` → `1,234.50`, `Thursday`, `english` | the throw |
| `anEmptyLanguageTagRendersInEnglish` | `locale: und` → `english`, `1,234.50` | the throw |
| `aTemplateErrorIsTheCodecsOwnFailureNamingTheTemplate` | `${#numbers.formatDecimal(}` → `TQL-LD-2831` naming `broken.html` | raw `TemplateProcessingException` |
| `ExportDeclarationsTest.aLocaleOnAPdfTemplateIsNotAdvisedAgainst` | pdf + template + `de-DE` over untyped columns → no violation; a zone alone and a csv → the advisory | the advisory |

**Bracket** (`work/export-hygiene-measurement/p6/bracket/`): HEAD 4 red; the fix 13/13 green;
**V-jvmdefault** (shape (a): undeclared → `Locale.getDefault()`) — exactly the GERMANY guard red
(`de-DE deutsch` rendered), hazard 67 made real; **V-nofold** (`und` used as-is) — exactly the
`und` guard red, now as `TQL-LD-2831 … Locale "" cannot be used` (the render inside the try dresses
it); **V-outside** (the render back outside the try) — exactly the template-error guard red.

### What this breaks

An undeclared export's template reads English while its typed columns follow the JVM default (the
columns' rung-4 deviation already exists and belongs to the temporal-semantics design). A
declared export's template now follows the declaration — the intended behaviour, and no shipped
or documented template moves. A template error's code changes from 2802 (route) / raw text (async)
/ 2810-wrapped raw text (job) to 2831 naming the template on every arm.

### Filed, not fixed (from P6's measurement)

- The Studio preview's columns are still locale- and zone-blind beyond the two keys threaded here
  (`ExportSpec.toWriteSpec` passes null for both; the preview now applies literals only) — the
  Studio backlog.
- `InboxNotifier.java:33`'s bare `Context` (JVM default) and `MailNotifier.java:109,152`'s ROOT
  subject render — the notifications line.

---

## P7 — the declaration path says what the runtime will do

### What was wrong

Items 11, 12, 6's lint half and 3's WARN — every one a case where lint or boot said nothing (or
the wrong thing) about a declaration the runtime then failed on:

- **Item 11.** `ExportDeclarations.violations:209-217` returned early for a job step without
  `format:` on the comment "the step never runs" — false: `ExportStepRunner:52-55` passes the null
  into `FileCodecs.require`, and every runner (`job run` on cp-full and cp-cli, `job rerun`, the
  runtime runner, the real scheduler, the ops API) failed with `TQL-LD-2801: No file codec for
  format 'null' - available: […] (the excel format needs …)`, naming neither job, step nor key —
  `job run` exit 1. Lint said `TQL-YAML-1041` first through its OWN private check
  (`ExportRules:59-62`), boot said nothing. A blank `format: ""` on a query-export was lint-silent
  (the predicate mapped blank → csv) and refused BOOT with `No file codec for format ''` naming
  no app and no route (`RouteCompiler:1406` defaults null only); on a file-export it worked as csv.
- **Item 12.** `as: delivered-{key}.zip` delivered the literal name on every runner (5c passes
  `{key}` through, `StepContext.interpolate:442-447`); lint was silent (positive control: `../`,
  `/`, `${` draw 1042). The same literal `{key}` was lint-silent on an export `filename:` WITHOUT
  `splitBy:` on job steps and routes — the converse of `ExportRules:194-201` did not exist. An
  unresolved placeholder (`{params.x}` undeclared, `{nope.nothing}`) rendered a SILENT
  `delivered-.zip`, the pre-5c symptom alive for every other typo. A push delivers exactly one
  file, so `{key}` can never resolve; `{steps.<id>.filename}` was the undocumented spelling.
- **Item 6's lint half.** Nothing judged the declared extension against the format:
  `users-{key}.tar.gz` under `format: csv` wrote csv bytes into entries named `.tar.gz`.
- **Item 3.** `FileCodecs.discover` kept the last codec put for a format with no line in any of
  four sinks (positive control passed): a module codec answering `csv` silently decided the async
  export, the job step and every import arm.

### The change

- **`ExportDeclarations.violations`** (decision 10): a job step with no or a blank `format:`, and
  a blank `format:` on any route, draw one `TQL-YAML-1041` INVALID violation on `export.format`
  ("export needs format: (csv, excel, or pdf)") naming the site — lint, boot, reload, job
  registration and `job run` (exit 2) alike, since all read the predicate. An ABSENT format on a
  route stays the documented csv default (`export-declarations.md` decision 22);
  `RouteCompiler:1406` is untouched for F82 slice 2. The linter's private 1041 for the step goes
  (5a's 1006 move, again).
- **`PushStepRules.lintDeliveredName`** (decision 7, ERROR under 1042): `{key}` in `as:`; a
  placeholder root outside `params|steps|batch|tenant`; a `params.<name>` absent from the job's
  `input:`. `{steps.<id>.filename}`, `{batch.businessDate}` and a plain name stay quiet.
- **`ExportRules.lintExportFilename`**, on job steps and routes: `{key}` without `splitBy:` →
  1041 ERROR; an extension that is not the format's (`.csv` / `.xlsx` / `.pdf`) → the new
  **`TQL-YAML-1045`** WARNING — never a refusal (`anExportNamedZipKeepsItsCodecsType` allows
  `notes.zip` for csv by design).
- **`FileCodecs.put`** (decision 8): one `System.Logger` WARNING when a DIFFERENT class takes a
  format another codec held, naming the format and both classes; last-wins kept; the same class
  twice (one codec on two loaders) is quiet. The lint rule and any refusal stay with F82 slice 2 —
  a refusal inside `discover` would fail every app carrying the module and crash `lint`/`job run`
  (RUN, variant).
- `docs/jobs.md` documents the context roots and `{steps.<id>.filename}`; `docs/file-transfers.md`
  the `format:` rule and the two filename lints.

### The guards, red before the fix (HEAD `35a66d60c`)

| guard | asserts | HEAD |
|---|---|---|
| `ExportDeclarationsTest.aMissingOrBlankFormatIsRefusedWhereTheRuntimeWouldFail` | job step with `null`, `""`, `"  "` → one INVALID 1041 on `export.format` naming job and step; routes: absent → none, blank → one | none |
| `ExportDeclarationsTest.aStepWithoutAFormatIsRefusedAndJudgedOnItsValues` (rewritten) | `export.format` AND `export.timezone` — the value arms still judge | pinned the early return |
| `JobCommandIntegrationTest.aFormatLessExportStepIsRefusedBeforeAnyExecutionRowExists` | `job run` exits 2 with `TQL-YAML-1041`, `job 'report.daily' step 'report'`, never `'null'`; no execution row | exit 1 |
| `AppLinterPushStepTest.aPushNameThePipelineCannotResolveIsAnError` | `delivered-{key}.zip`, `{nope.nothing}.csv`, `{params.x}.csv` → 1042 ERROR; `{steps.extract.filename}`, `users-{batch.businessDate}.csv`, `plain.csv` → quiet | silent |
| `AppLinterExportStepTest.aFilenameThePipelineWouldDeliverWronglyIsSaidSo` | `report-{key}.csv` without splitBy → 1041; `users-{key}.tar.gz` + splitBy → 1045 WARNING, no 1041; `report.csv` quiet | silent |
| `AppLinterRouteExportTest.aLiteralKeyWithoutSplitByIsAnErrorOnARouteToo` | the route arm: 1041; `notes.zip` for csv → 1045 WARNING | silent |
| `FileCodecsTest` (new, a JUL handler with a control line) | `of(First csv, Second csv)` → one WARNING naming `'csv'` and both classes, `Second` wins; `of(First csv, Second excel)` and `of(First csv, First csv)` quiet | no line |

**Bracket** (`work/export-hygiene-measurement/p7/bracket/`, core + yaml jars reinstalled per
column): HEAD 6 red; the fix 3/3 + 75/75 + 6/6 + 17/17 + 34/34 + 1/1 green; **V-lintonly** (the
format violation INERT) — exactly the predicate's INVALID assertion and the CLI exit-2 guard red
(hazard 77: an `AppLinter` test alone is green); **V-keyonly** (the root arm gone) — exactly the
`{nope.nothing}` case red; **V-nofilename** (the route arm's call gone) — exactly the route guard
red; **V-sameclass** (WARN on any replacement) — exactly the quiet guard red.

### What this breaks

A job step without `format:` that "worked" (booted, then failed every run) is refused at lint,
boot and `job run` (exit 2, was 1). `format: ""` on a file-export goes from "works as csv" to
refused (a typo, not a choice — decision 10). A push `as:` carrying `{key}` or an unresolvable
placeholder, and an export `filename:` carrying `{key}` without `splitBy:`, are lint errors —
lint rules, not predicate arms, so a running app keeps delivering what it did until the
declaration is fixed (pre-1.0, no users). An app declaring `notes.zip` for a csv gains a lint
warning.

### Filed, not fixed (from P7's measurement)

- The `TQL-LD-2801` hard-coded excel hint (already filed) — untouched here.
- `tesseraql lint` crashes with a stack trace on an unquoted `as: {nope}` (a YAML flow mapping,
  `TQL-YAML-1001` out of `AppLinter.lint`) — the filed lint-crash family; every fixture here is
  quoted.
- The three-runners-two-module-sets drift for an undeclared `work/modules` jar — F82 slice 2.

---

## P8 — the card and the status JSON under a prefix, and what a failed export says on the wire

### What was wrong

- **N1 (unfiled, measured by two attackers through `host`).** Under a base-path prefix — every
  `tesseraql dev` / `host` stack, "a prefix is universal" (`base-path-emission.md`) — the status
  JSON's `fileUrl` was `urlPath + "/" + id + "/file"` with no `BasePath.url`
  (`FileTransferStatusProcessor:94`) → 404 through the gateway; the card's Download href was
  DOUBLE-prefixed: `:110-111` built `statusUrl` with `BasePath.url`, `JobCards` appended `/file`,
  and `job-card.html:49` wrapped it in `@{…}`, which the base-path link builder prefixed again;
  the cancel form (`:31-32`) had the same shape. The poll `hx-get` was emitted without `@{}` and
  worked, which is why polling succeeded and only the terminal links died. `FileTransferIntegrationTest:138`
  asserted `endsWith(transferId + "/file")` — exactly how this drifted; an in-process
  `TesseraqlRuntime.start` has no prefix and is blind to both.
- **Item 8, the wire half (decision 5).** Nine failure shapes on a `file-export` route answered
  the identical reason-less status JSON (no `errors` key at all) and the import-shaped card
  "Nothing was written. 0 row(s) were rejected." with a link-less FAILED badge. The reason existed
  only on the execution row — since P3 with its code — and the raw `exit_message` carries the
  driver's text, SQL fragments and 2831's absolute app-home path, readable through any
  file-export route by anyone holding any transfer id (N2, filed to the edge line, decision 12) —
  the argument against projecting the raw text.

### The change

- `FileTransferStatusProcessor`: `fileUrl` through `BasePath.url`; the card's `file` and `cancel`
  emitted as the wire URLs they already are (`th:href="${c['file']}"`, `th:action="${c['cancel']}"`),
  like the poll — never a second link expression.
- **`TransferStatus` gains `exitMessage`** (breaking on the record; the two older constructors
  stay) and `failureCode()`: the `TQL-XXX-nnnn` prefix P3 made universal, or null.
  `JdbcFileTransferService.status()` hands the execution's message over.
- The wire: a FAILED export's JSON carries `code` and `reason` — `JobCards.reason(code, catalog,
  locale)`, the catalog's `tql.job.reason.<code>` (ten codes in `en.yml` and `ja.yml`) else
  `tql.job.reason.other` ("The export failed."). The card's failure line for an export is
  `tql.job.exportFailedBody` = "{reason} ({code})"; imports keep their sentence. The raw message
  is never projected.
- `OpenApiGenerator`: the transfer-status schema gains `expectedRows` (it never had it), `code`
  and `reason`.
- The operations console's transfer row links its id to the execution (the transfer id IS the
  execution id) — the raw reason, one click away, behind the ops permission.

### The guards, red before the fix (HEAD `71f9434a9`)

`BasePathEmissionIntegrationTest` — the stack harness (`HostContext.stack().forApplication("/shop")`),
the ONLY harness that sees a prefix — gains a `file-export` route and a failing twin
(`select 1 / 0`); the first FAILED-export integration test in the repository:

| guard | asserts | HEAD |
|---|---|---|
| `aCompletedExportsLinksAreSinglePrefixedAndAnswer` | POST (with the CSRF field) → COMPLETED; the status JSON's `fileUrl` starts `/shop/api/things/export/<id>/file` AND a GET of it as emitted answers 200; the card's Download `href` is the same single-prefixed URL AND answers 200 | `fileUrl` `/api/things/export/…` (no prefix); the card `/shop/shop/…` |
| `aFailedExportSaysWhyWithACodeAndNoDriverText` | the FAILED status JSON has `code` = `TQL-LD-2810` and a `reason` containing "statement", and no `division by zero` anywhere; the card carries the code and neither the driver's text nor "Nothing was written" | no `code` key |

**Bracket** (`work/export-hygiene-measurement/p8/bracket/`, core + operations + yaml + compiler
+ ops-ui jars reinstalled per column): HEAD 2 red; the fix 12/12 + `FileTransferIntegrationTest`
19/19 green; **V-nojsonprefix** — exactly the links guard red on the JSON half; **V-cardlink**
(`@{…}` back on the href) — exactly the links guard red on the card half, reading `/shop/shop/…`;
**V-rawreason** (`reason` = the recorded text) — exactly the failed guard red on the driver's text
(hazard 60: a guard reading the DB row would have been green).

### What this breaks

`TransferStatus` gains a component — breaking on the record per the `expectedRows` precedent; the
older constructors stay. The status JSON of a FAILED export gains two keys; the card's failure
sentence changes for exports. Nothing changes on a deployment without a prefix except those two
keys and the sentence.

### Filed, not fixed (from P8's measurement)

- **N2**: any file-export route's status/file subtree serves any transfer id in the app (no route
  check) — the edge/router or security line (decision 12); the `code + sentence` shape here is
  the mitigation.
- The transfer span invisible to the ops traces API (no `app` attribute) — the ops line.
- `rowCount` 0 on every failed export — the error-hygiene line.

---

## Guard hazards the pull requests were built against

Measured or found by construction during the measurement phase (record §5); each pending pull
request builds its guards against these variants and proves them red on HEAD first.

- **P1**: ASCII keys are identical under OEM conversion — use `受注`/`東京`. Assert the extra
  field in BOTH the central directory and the local header, by field id, never `length > 0`.
  `setTime(<any 1980-2099 time>)` writes nothing. A determinism probe inside one 2-second DOS-time
  slot is green on HEAD. An `unzip` shell-out is silently green where the binary is absent and RED
  on a correct tree under the `C` locale — assert bytes. `𠮷`×51 is not a lone surrogate (the cut
  lands on a pair boundary); use an odd prefix.
- **P2**: a csv or grid failure of the MAIN source on the sync route already deletes the writer
  spool; the failure must come from the source iterator or the spool encoder on a buffered codec,
  a `splitBy:` or a NAMED source. A failure after the drain is green. A database error fires at
  `executeQuery` on PostgreSQL unless the failing row is past the fetch batch on a streaming plan —
  use the row cap. A counting fake that registers on the first `write` misses a source that throws
  on its first `hasNext()`. A `tql_temp_spool` count driven through `job run` was blind until P0.
  A failure BEFORE `createWriter` is red on the drain half and green on the writer half. For the
  ceiling: 65,535 ASCII / 21,845 `あ` pass today; round-trip through the READER.
- **P3**: a 2000-character reason fits — use 2100. Truncating `bindFinish` alone moves the cliff
  to `failStep`. `status != RUNNING` on the step's transfer execution is reaped to a false 4212.
  A `TqlException` failure is already prefixed; the excel GRID never raises the per-value shape.
  Piece 1's guard is the `\tat ` frames in a captured stderr (SLF4J is capturable; JUL is not).
- **P4**: 32,767 characters are green in all three modes; assert the CODE and column + row, not
  "throws". Re-reading the grid's file with any reader says valid. A report-mode guard asserting
  "no exception" is blind — jxls swallows it. An openpyxl-authored template loses static cells on
  the control too — author fixtures with POI. A 100,000-character value before P2 fails with 2855.
  A missing-template test at lint, boot or `job run` is green since #1306 — delete or empty the
  template BETWEEN boot and request. For 4b: 1,048,575 data rows are green; the grid's row 0 is
  the header.
- **P5**: the derived-header guard must stay red on a rule-1-only variant (three variants: HEAD,
  rule 1, rule 2). No `ResultSetMetaData` exists at the unit level — run through `ResultSetRows`
  on Testcontainers. `ExportByteOrderMarkIntegrationTest#anEmptyMarkedExportIsExactlyTheMark` is
  THE test that goes red — prove it red on HEAD before reasserting `MARK + "Name\r\n"`.
- **P6**: a template that only echoes `${…}` is byte-identical under every shape; use a utility
  with `'DEFAULT'` under de-DE and a `#{key}` with a `_de.properties` sibling. `en-US` renders like
  ROOT. Pin the JVM default with `Locale.setDefault(GERMANY)` in the test. Cover `und`.
- **P7**: an `AppLinter` test for the format-less job step is green (the linter's private 1041
  already fires) — the red tests are boot and `job run` (exit 2, the step named). A `format: ""`
  job-step lint test is green; the route blank is the red one. `declarationsHold` is app-wide.
  An unquoted `{key}`-first YAML scalar is a flow mapping (`TQL-YAML-1001`) — quote every fixture.
  `System.Logger` on the runtime test classpath bypasses a stderr capture — attach a JUL handler.
- **P8**: an in-process `TesseraqlRuntime.start` harness has no base path and is blind to both
  dead links; `FileTransferIntegrationTest:138`'s `endsWith` is how this drifted. Drive the stack
  harness (`BasePathEmissionIntegrationTest`) and assert the full prefixed path AND a 200.
- **Repo-wide**: `-am` builds dependencies, not dependents — every pull request that adds or moves
  an LD code puts `tesseraql-docs-reference` in its verify set. Every "no log line" negative needs
  the four-sink positive control. Every census as `git grep` with the `:(glob)` pathspec.

---

## Scope out, each with its destination

- Which loader the sync route discovers codecs on, item 3's duplicate-format lint, any
  duplicate-format refusal, the three-runners-two-module-sets drift → **F82 slice 2**.
- The `RouteReloader` fingerprint hole (a template not an immediate child of the route directory
  is outside `digestDirectory`) and the Excel codec's missing template confinement root → **a
  reloader filing**.
- `splitBy:` uncapped for every streaming codec, lint 5310 silent; the discarded named source that
  runs, spools and can fail the export → **the export-declarations design line**.
- N2, any transfer id through any file-export route's subtree → **the edge/router or security
  line** (decision 12).
- The transfer span invisible to the ops traces API; 2831's absolute path and the 2853/2855 value
  snippets in `exit_message`; `rowCount` 0 on every failed export → **ops and error-hygiene
  lines**.
- Import-side asymmetries (a 3-byte marked empty upload vs a 0-byte one; the skipped 2826 check;
  `JxlsFileCodec.read`'s NPE on a blank header cell) → **the csv-import line**.
- `InboxNotifier`'s bare `Context` and `MailNotifier`'s ROOT subject → **notifications**; the
  grid workbook's wall-clock `dcterms:created` → **deterministic output**; Studio's locale-blind
  export preview → **the Studio backlog**; everything temporal → **the temporal-semantics design**.

---

## The audit records, amended by this slice

- [`audit-medium-leads.md`](audit-medium-leads.md): S1 and S2 enter "Defects surfaced that the
  audit does not carry" (P0); leads 21, 22 and 24 gain their measured mechanism and this record as
  destination.
- [`download-name-and-bytes.md`](download-name-and-bytes.md) "Filed, not fixed", the
  export-hygiene bullet: the ZIP mechanism (the absent extra field), item 2's narrowing, item 3's
  arms, item 6's real width, the 32,768 band, the zero-row width — each pull request amends the
  clause it closes.
- [`export-declarations.md`](export-declarations.md) "Filed, not fixed", the export-hygiene
  bullet, and I16 (`temp.store: db` — the sync route only): S1 is the reason the async download
  under db was never observed there.
