# Export hygiene: what an export leaves behind, and what it says when it fails

> **Status: in progress.** Nine pull requests, in this order, each branched from fresh
> `origin/main` after the previous one merged. **P0** — under `tesseraql.temp.store: db` or
> `blob` a download, a push and the retention sweep address the spool by its own id, a failed
> download is not recorded as delivered, and `tesseraql job run` honours the declared store:
> **shipped with this record** (the pull request that registers this file in both internal-doc
> lists). **P1** the split bundle — every entry carries the extended-timestamp field and the
> ZIP epoch, the key's bound cuts on a code-point boundary and keeps its case, the bundle's name
> drops the placeholder with its separators wherever it stands: shipped as P1. **P2** the spool
> leaks and the 65,535-byte ceiling; **P3** what a
> failed export says; **P4** the Excel codec's own refusals; **P5** the zero-row header; **P6** the
> print template's locale; **P7** the declaration path; **P8** the card and the status JSON under a
> prefix — pending. Each pull request flips its own line here when it merges.
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

## Guard hazards for the pending pull requests

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
