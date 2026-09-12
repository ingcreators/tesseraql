# An export declaration is refused, or it takes effect

> **Status: designed.** Four pull requests, in this order, each branched from fresh
> `origin/main` after the previous one merged. **5-0** — the codec renders every value the
> driver hands it (the Excel grid/placement NULL cell, the `time` column, the `LocalTime` /
> `OffsetTime` treatment): **shipped with this record as #1305** (the pull request that registers this file
> in both internal-doc lists). **5a** — every literal export declaration is judged once, at lint
> and at boot, with one code (`TQL-YAML-1063`): pending. **5b** — a request-sourced value is
> refused before the SQL runs (`TQL-FIELD-2001`, code `timezone` / `locale`), the fallback chain is
> one rule on every arm, a job reads `tesseraql.files.*`, and a failure while writing the document
> has its own code (`TQL-LD-2802`): pending. **5c** — a valid `splitBy:` takes effect on a job step
> and a `file-export` delivers its bundle as the ZIP it is: pending. Each pull request flips its own
> line here when it merges. Closes F126 of [`audit-medium-leads.md`](audit-medium-leads.md) with
> 5a and 5b; 5-0 closes the two codec crashes the measurement found under it.
>
> **The plan this document replaces was wrong in six load-bearing places.** It filed the bad zone
> as a 500 `TQL-ROUTE-5000` that "no main code catches": the code is `TQL-SQL-2500`, raised by
> `SqlStep.export`'s catch-all, which files a codec failure as "SQL execution failed" naming the SQL
> file — after the extraction ran. It called the locale "safe by contrast": a mistyped locale
> renders `Locale.ROOT` at 200 on every export arm, and on the two **import** arms `import.locale:
> de_DE` parses `1234,50` as `123450.00` and completes. It drew the three defects on the path
> wrong: N3 (a locale twin for the catalog lint) is dead — `codes` never reaches any export; N2
> (the inert declaration) is inert only for a column with neither `type:` nor `format:`, on `csv`
> and `pdf` only, and its quoted numbers describe a `timestamptz` — on a zoneless `timestamp` the
> **typed** cell is the one that moves with the host; and the harmful defects were unfiled: any
> Excel grid or placement export with one NULL cell answers 500 (high, since v0.1.0), any export of
> a `time` column answers 500 on five of six drivers, and a typed `datetime` on a zoneless
> `timestamp` is shifted by (declared − JVM) zone. It treated the configuration key as static and
> the fallback as "source → config → JVM": a per-request source resolves through the key, an
> unresolved source skips the configuration (pinned by a test), and a job step never reads the
> keys at all. It said "boot refuses an unknown format": query-export only — a `file-export` or a
> job step carries `format: foo` to its first request or run; and it did not know that `splitBy:` on
> a job step can never run, nor that a `file-export` split bundle is served under the per-document
> name and the codec's content type. Finally it said slice 5 and 4c share `CsvFileCodec.write`:
> they do not — this slice's edit sites are `ColumnValues`, `JxlsFileCodec`, `ExportDeclarations`
> (new), `ExportRules`, `RouteCompiler`, the three binders, `SqlStep`, `ExportStepRunner`,
> `StepContext`, `JdbcFileTransferService` and `SplitExport`. **Read the decisions below, not the
> plan.** The measurement record (`work/slice5-measurement/MEASUREMENT.md`, 860 lines, not in the
> repository) is the evidence; every "RUN" below names a log under `work/slice5-design/` (this machine only).

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by 5-0, with the
registration asserted in `InternalDocsSyncTest` (its two set differences are green on a record in
neither list). Site-excluded, so the prose lint and the vocabulary guard never read it; the error
index skips internal documents, so the codes named here move no reference page.

---

## The measured premise

Everything in this section was RUN against `44bc299fe` (`0.17.0-SNAPSHOT`, the 11:18 UTC `.m2`
install) by eleven measurers, eleven attackers, three scope lenses, the adjudicator and the four
synthesizers; nothing below is inferred from the plan.

### Today's temporal contract — stated precisely, and NOT changed by this slice

- On **`csv` and `pdf`**, a column renders through the export's `locale:`/`timezone:` only when it
  declares `type:` (`date` / `datetime` / `number`) or `format:`; a column with neither is written
  as the driver object's `toString()` (`2026-01-15 22:30:00.0` for a `java.sql.Timestamp`).
- On an **Excel grid or placement** export every temporal cell is zoned into the declared zone
  (the JVM zone when none is declared), typed or not.
- In **jxls report mode** (`format: excel` + `template:` without `startCell:`) nothing is zoned and
  none of `timezone:`, `locale:`, `type:`, `format:` is read — jxls receives the raw driver values.
- **A zoneless column's zone is the JVM zone**: `toZoned` reads a `timestamp` as an instant in
  `ZoneId.systemDefault()`, so the typed cell of a zoneless column moves with the host (`07:30` on a
  UTC host, `15:30` on Los Angeles, `02:00` on Kolkata under `timezone: Asia/Tokyo`) and the untyped
  cell is host-stable except inside the host zone's DST gap. A `timestamptz` is host-independent.
  Three semantics across five dialects (instant-in-JVM-zone on PostgreSQL / SQL Server / DuckDB
  `timestamp`; wall clock on MySQL `DATETIME`; ignored on Oracle `TIMESTAMP` and SQL Server
  `datetimeoffset`; Oracle `TIMESTAMPTZ` renders `oracle.sql.TIMESTAMPTZ@hash`). This is decision 13
  of the measurement: **not this slice's** — the temporal-semantics design, merged with the
  result-column-types design, owns it.
- **After 5-0, one addition:** a time of day (`java.sql.Time`, `LocalTime`, `OffsetTime`) is never
  zoned by the codec on any surface — it has no date and no instant; an `OffsetTime`'s offset is
  dropped, never applied. A PostgreSQL `time with time zone` is not an `OffsetTime`: pgjdbc hands it
  over as a `java.sql.Time` already moved into the JVM zone (`'22:30+09:00'::timetz` → `13:30:00` on
  a UTC JVM, `22:30:00` on JST, `08:30:00` on New York — `s-5-0/logs/pg-time-probe.log`), and
  `timezone:` does not correct that; the JSON path has the identical shift.

### The per-key × surface class summary (m5's 482 cells, corrected by a5)

Seven classes: **A** refused at lint AND boot · **B** lint error only · **Bw** lint warning only ·
**C** boot only · **D** refused at neither, fails at the first request or run · **E** refused at
neither, silently inert or silently different · **F** takes effect. No export RULE is in class A —
the twelve A cells are YAML type failures (`maxRows: abc`). 52 B (27 of them inert at the runtime,
20 failing at the first request), 57 Bw, 27 C (15 query-export, 11 file-export, one job — the
config zone; a job step itself is never checked at boot), 68 D (29 of them job steps), 148 E
(twenty-one of which are decision 7's documented streaming no-cap, not silences), 118 F (eleven of
them malformed values nothing refuses). The B and D cells are this slice's raw material: every one
of them becomes A (refused at lint and at compile, one code), a boot WARNING beside the lint error
(the inert keys), or a lint warning (the advisories), by the mapping in decision 13.

### What is live, per input path × surface

A bad zone from a route literal, the config key, a `query./params./body.` source (only when the
route declares the input — otherwise it silently never resolves), a request header named after a
declared input, an IdP `zoneinfo` claim (string, number, array or nested object) or a job-step
literal reaches `ZoneId.of` unvalidated. On a synchronous query-export it answers **500
`TQL-SQL-2500`** (67-byte JSON, no CSV byte, one `PipelineRunner` ERROR line echoing the raw value
unbounded — a `%0A` forges a log line) **after the extraction SQL ran and the first 1,000-row batch
was evaluated** (or the whole extraction drained and spooled on pdf/placement); on a file-export
**`202` then `FAILED` with `rowCount 0` and no reason on the wire** (the reason is in
`tql_job_execution.exit_message`); on a job step **`TQL-LD-2810: Export step failed: …`** on every
firing (417 FAILED transfer rows in 14 minutes under a cron, the ops health at WARN). Lint: 0
findings on every spelling. Boot: silent on every arm. A blank literal cannot opt out of a bad
config key. The jxls report mode ignores every one of these keys and `?tz=`; a zero-group `splitBy:`
never parses the zone; a `pdf`/placement route above its row cap fails with `TQL-LD-2850` first.

The locale twin: `Locale.forLanguageTag` never throws — `ja_JP`, `de_DE`, `japanese`, `xx-YY`
render `Locale.ROOT` at 200 on every export arm; on `import.locale` the same typo corrupts numbers
(`1234,50` → `123450.00`, COMPLETED). `locale:` is inert on `format: excel` in every mode.
`request.locale` is the one laundered source (RFC 4647 lookup against `tesseraql.i18n.locales`).

The fallback chain as measured: a route literal, else the config key at compile time
(`RouteCompiler.formatDeclaration`, blank counts as absent); a source that resolves to nothing →
the JVM default, **never the key** (`FormatSourcesTest.unresolvableExpressionsYieldNullForThePlatformDefault`
pins it); a job step → its literal else the JVM (`ExportStepRunner:45-48` never reads the keys —
the released reference promises otherwise); `tesseraql.files.timezone: query.tz` resolves per
request with no `input:` to bind against (undocumented).

Three more facts the four PRs stand on: `SqlStep.export`'s `catch (Exception)` at `:350-351` is
what files a codec failure as a SQL failure; `toZoned` (`ColumnValues.java:140-151`) has no `case
null` and `java.sql.Time.toInstant()` throws inside it, and the two Excel writers ask it before their
own null arm; `StepContext.interpolate:425-439` renders an unresolved `{key}` empty before
`SplitExport.write:53-57` demands it, so a job-step `splitBy:` fails every run with `TQL-LD-2858`
blaming the author, while a file-export `splitBy:` transfer is recorded under the per-document
pattern and served under the codec's content type over ZIP bytes.

---

## Decisions

Each decision names the alternative rejected and the evidence file. Numbers are cited by the
per-PR sections and the checklists. "RUN" always names a log under
`work/slice5-design/` (`s-5-0/`, `s-5a/`, `s-5b/`, `s-5c/`, `adjudication/`).

### The slice

**1 — Four pull requests, in this order, and the record rides the first.** 5-0 → 5a → 5b → 5c,
each from fresh `origin/main` after the previous merged (the squash-merge trap: a branch cut from a
sibling conflicts the moment the sibling merges, and a conflicted PR skips CI and looks green).
5-0 lands this record complete — every PR's design, each with a "pending" status line — and
registers it in both internal-doc lists with the registration asserted. *Rejected:* a record-only
PR first (5-0 Q3, 5a OQ11) — the standing design-doc-first rule is satisfied by the record riding
the first PR (`download-name-and-bytes.md` decision 18 is the precedent), and it puts the HIGH fix
one PR closer; 5c creating the skeleton if the order changed (5c OQ6) — the order does not change.

**2 — One predicate, one class, and its public surface is fixed here.**
`io.tesseraql.yaml.app.ExportDeclarations` (beside `DeclaredRoles`, outside `lint`), owned by 5a:
the typed constants `INVALID_VALUE` (`TQL-YAML-1063`, minted), `INAPPLICABLE` (1005), `INCOMPLETE`
(1041), `UNUSABLE_TEMPLATE` (1006); `CONFIG_KEYS`; `SOURCE_EXPRESSION`
(`(principal|query|body|params|request)\..+`); `enum Kind { INVALID, INERT, ADVISORY }`; `enum
Surface { QUERY_EXPORT, FILE_EXPORT, FILE_IMPORT, JOB, CONFIG }`; `record Violation(code, kind, key,
message)`; `record Site(app, subject, surface, inputs, principal, declaredBody)` with the factories
`Site.route(app, RouteDefinition)` (the ONE classification of a route: recipe → surface; declared
inputs; `security != null && auth != null && auth != "public"` after the loader merged the
defaults; `effectiveInputPolicy().rejectsUnknownFields()`), `Site.step(app, jobId, stepId)`,
`Site.job(app, jobId)`; and the methods `isSourceExpression(String)`, **`Optional<String>
zoneProblem(String)`**, **`Optional<String> localeProblem(String)`**, `isFormattableLocale(String)`,
`bounded(String)`, `violations(Site, ExportSpec, Path)`, `violations(Site, ImportSpec)`,
`configViolations(app, AppConfig)`, `configViolations(app, key, value)`, `requireJob(app, JobFile,
Consumer<String>)`, `require(List<Violation>, Consumer<String>)`, `missingBlock(Site)`. 5b consumes
exactly four of these (`isSourceExpression`, `zoneProblem`, `localeProblem`, `bounded`) through one
adapter (`RequestFormats.judged`); `FormatSources` delegates its regex to the first.
`zoneProblem`/`localeProblem` answer the problem sentence — `'<bounded>' is not a time-zone id the
JDK knows (expected a region id such as Asia/Tokyo - case-sensitive - or an offset such as
+09:00)` / `'<bounded>' is not a language tag the JDK can format (expected e.g. en, ja-JP)` — and
the predicate's own `zoneLiteral`/`localeLiteral` arms build their `Violation` from them plus the
site prefix and the source hint, so lint, boot and the binder cannot drift. *Rejected:* 5b's own
`ZoneId.of`/`Locale.Builder` calls (the `PolicyCodes` and `TQL-SEC-4048` lint/boot drifts are the
measured precedents — MEASUREMENT §2.6 item 10); 5b's stand-in name `io.tesseraql.yaml.files.ExportValues`
(5b said 5a owns the name). *Adjudication:* the 5a prototype exposes `isFormattableLocale` and a
private `zoneLiteral` only; the two `Optional<String>` methods are an extraction, not a behaviour
change — RUN `adjudication/probe/locale-rule-parity.log`: 5a's rule and 5b's stand-in agree on all
28 of 5b's guard fixtures (0 mismatches); the only divergence is the four CLDR aliases decision 15
admits.

**3 — `bounded()` is one implementation, 5a's.** Every author- or caller-controlled fragment in a
message goes through it: code points whose `Character.getType` is CONTROL, FORMAT, LINE_SEPARATOR,
PARAGRAPH_SEPARATOR, SURROGATE or UNASSIGNED become `?`; the result is cut at 40 code points (never
inside a surrogate pair) and, when cut, ends in ASCII `...` (43 characters for a 300-character
value). *Rejected:* 5b's stand-in — a `[\p{Cc}\p{Zl}\p{Zp}]` regex ending in U+2026 — RUN
`adjudication/probe/bounded-parity.log`: it passes U+202E (a right-to-left override) and a lone
surrogate into the log line, and puts a non-ASCII byte into the one string whose purpose is a
clean line; 5b's U13 therefore asserts `contains("...")` and a quoted length ≤ 43 (it asserted `…`
and ≤ 41 against the stand-in). 5b's variant V-cc-ascii (Java's ASCII-only `\p{Cntrl}`) stays red
under 5a's implementation (U+0085 NEL is CONTROL).

**4 — Codes and message keys.** `TQL-YAML-1063` = a literal the runtime cannot honour where it
reads it, refused at lint AND boot; `TQL-YAML-1005` = a key that cannot apply where declared (lint
error + boot warning) or is honoured less than it reads (a warning on both sides); `TQL-YAML-1041`
= a missing piece; `TQL-YAML-1006` = a template that is not there or the wrong kind. `ExportRules`
spells 1041/1005 through the typed constants (`ErrorCodeUniquenessTest` RUN green without an
`ANTICIPATED` entry; red on String twins — `s-5a/logs/docsref-SU.log`). Request time:
`TQL-FIELD-2001` with `code: timezone | locale` (the `InputBinder` field error, 400). The
document-write failure: `TQL-LD-2802` (`SqlStep.DOCUMENT_WRITE_FAILED`, minted; 2801 is the
codec-missing neighbour), 500 by the LD default arm. Message keys, in BOTH `en.yml` and `ja.yml`
and in `BuiltinCatalogParityTest`'s named list: `tql.input.timezone`, `tql.input.locale`,
`tql.input.claim.timezone`, `tql.input.claim.locale`. Never `TQL-ROUTE-3103` (live at
`RouteReloader:50-52`); `TQL-ROUTE-3101` stays the compiler's own for `after:` on a query-export.
No literal `TQL-…` in any new `src/main` javadoc or comment (`ErrorIndex` indexes it as a raise
site).

### PR 5-0 — the codec

**5 — A NULL is "not mine" inside both core normalizers.** `toZoned(null, zone)` and
`toLocalTime(null)` return null; the Excel writers keep asking them first and keep their own null
arms. *Rejected:* a null test in the writers before the helpers (variants V7, A3 — every codec
guard green; the public API stays a trap for the next caller, Studio's `renderExportPdf` first).
Pinned by `ColumnValuesTest.aNullValueIsNotATemporal` (red on V7/A3). Evidence: `s-5-0/logs/matrix.md`.

**6 — A time of day is its own value class and never a `ZonedDateTime`.** New
`ColumnValues.toLocalTime(Object)`: `java.sql.Time` (pgjdbc, H2, MySQL, MariaDB, SQL Server) →
`Time.toLocalTime()`, `LocalTime` (DuckDB) → itself, `OffsetTime` (H2/DuckDB `time with time
zone`) → `toLocalTime()`, anything else → null. `toZoned` gains `case null -> null` and `case
java.sql.Time _ -> null` before the `java.sql.Date` arm (the reverse order is a javac dominance
error). *Rejected:* a `Time` arm inside `toZoned` at `LocalDate.EPOCH` (V3 — every Excel cell
becomes `25569.9375`, `type: datetime` invents 1970-01-01; red on 9 guards and 5 IT methods);
swallowing the exception in the `java.util.Date` arm (V4 — `format:` ignored, Excel writes text;
red on 12); leaving `Time` to the `java.util.Date` arm with the callers asking `toLocalTime` first
(Z1 — `toZoned(time)` still throws for the next caller; red on `aSqlTimeRendersAsWallClockText`).

**7 — The wall clock of a `java.sql.Time` is the one the driver built in the JVM zone, read back
by `Time.toLocalTime()`.** Host-independent by construction: pgjdbc (RUN under UTC / Asia/Tokyo /
America/New_York) builds the `Time` in `TimeZone.getDefault()` and `toLocalTime()` reads the same
fields back. The deprecated basis is the accepted contract, because the two "modern" rewrites are
the defects: decoding `getTime()` as UTC seconds-of-day (A1 — right only on a UTC JVM, the CI host;
`13:30` for `22:30` on a JST runtime) and zoning the `Time` as an instant into the export zone (A2 —
`07:30` under `timezone: Asia/Tokyo` on a UTC host). Pinned by three flip guards (core, Excel
grid, the IT), each red on A1 and A2 under BOTH host zones; every other A1/A2 cell moves with the
host (`s-5-0/logs/bracket-UTC.log`, `bracket-Asia_Tokyo.log`).

**8 — The rendered text and the workbook cell.** On `csv`/`pdf`, untyped:
`DateTimeFormatter.ISO_LOCAL_TIME` (`22:30:00`; a fraction only when present — `22:30:00.5` for a
`LocalTime` carrying one); with `format:`: the pattern over the `LocalTime` in the export's locale
(`hh:mm a` → `10:30 午後` under `ja-JP`, `10:30 PM` under `en-US` — A6, the unlocalized arm, is red
under both). In a workbook: a time-fraction cell (`22:30` = `0.9375`) — grid under `hh:mm:ss` or
the column's `format:`; placement under the declared `format:` when set, else the template's
prototype style as it is (`columnStyles`, `JxlsFileCodec:267-284`; a `General` prototype shows
`0.9375`, as a date shows its serial today). *Rejected:* `LocalTime.toString()` (the JSON path's
text — drops zero seconds, so one column reads `22:30` and `22:30:05` on alternate rows, and
decision 12 becomes unprovable on HEAD because DuckDB already passes exactly that through); a
string cell (V5 — `format:` inert on Excel while it formats csv/pdf, no sorting; red on 4 guards and
5 IT methods); no default cell format (V6). Cost accepted: the JSON API (`22:30`) and the export
(`22:30:00`) disagree on a zero-second time until the result-column-types design aligns the JSON
path.

**9 — The zone and the `type:` never touch a time of day; an `OffsetTime`'s offset is dropped.**
`type: date`/`datetime` on a time column invent no date (A7 red); `timezone:` shifts nothing; an
offset that is NOT the export zone's (`22:30-05:00` under Asia/Tokyo, `22:30+09:00` under UTC)
renders `22:30:00` in core, the grid and placement (K1/K2/K3 — shifting into the export zone — and
V9 — shifting to UTC — all red). Reach, scoped by surface: `csv` and the Excel grid stream rows and
render an `OffsetTime`; pdf, placement, report and any `splitBy:` spool every row through
`SpooledRows.write`, which has `java.sql.Time` and `LocalTime` arms and no `OffsetTime` arm →
`TQL-LD-2853` there, as today. On PostgreSQL the question does not arise (the driver has already
zoned a `timetz` into the JVM zone). *Rejected:* applying the export zone (decision 12's
alternative — wrong across DST half the year, since there is no date, and different from the JSON
path).

**10 — Sub-second precision follows the driver's value class.** A `java.sql.Time`'s milliseconds
are dropped (`Time.toLocalTime()`, the JSON precedent) — pinned: `new Time(Time.valueOf("22:30:00").getTime() + 500)`
renders `22:30:00` (V10 keeps them: `22:30:00.5`, red); pgjdbc hands exactly that object for
`'22:30:00.5'::time(3)`. A `LocalTime`/`OffsetTime` keeps its nanos. So `time(3)` renders
`22:30:00` on five dialects and `22:30:00.5` on DuckDB — recorded, not resolved. *Rejected:* keeping
the millis by `getTime()` arithmetic — depends on the JVM zone through `getTime()` and disagrees
with JSON. The design's "no guard can pin it" was wrong.

**11 — Untouched by scope.** The eight date-time arms of `toZoned`; jxls report mode (a `Time`
renders `25569.9375` under `General` before and after; a NULL renders blank — it never asks
`toZoned`); `microsoft.sql.DateTimeOffset`, `oracle.sql.*`; the drivers' own range behaviour (MySQL
Connector/J refuses a TIME outside a day at `getObject`; MariaDB wraps modulo a day and the fix
renders the wrapped value at 200). 5-0 adds no code, message key, schema field or 4xx; it removes
three failure shapes (500 on six route shapes, FAILED rowCount 0 on file-export, `TQL-LD-2810 …
null` on a job step — all RUN on HEAD in the IT).

### PR 5a — the literal, judged once

**12 — One bound record, one predicate, every altitude.** `RouteRules:182`, `JobRules:166`,
`RouteCompiler:1379/1547`, both job-map fills (`TesseraqlRuntime:1346-1351`, `:1434`), `RouteReloader`
and `JobCommand` hold the same Jackson-bound `ExportSpec`/`ImportSpec` and call
`ExportDeclarations.violations` on a `Site` built by one factory. Lint reports the list (a finding
per violation); the compiler refuses it in `buildQueryExport` (BEFORE `require(format)` at `:1388`
— F82's line, untouched), `buildFileExport` (after a `spec == null` → 1041 `missingBlock` guard),
`buildFileImport`, and once per full `compile()` for the two config keys; `RouteReloader.reload()`
judges the config keys once per reload and refuses the reload whole (the last good routes keep
serving); `TesseraqlRuntime` refuses at both job-map fills (main app and mounted apps) — inside
`start`'s `try`, so `dev` and `host` print one line and exit 2; `JobCommand.run`/`rerun` call
`declarationsHold` before wiring — every job (a chain may fire any) AND the two config keys — one
line, `return 2`, no execution row; `list` never refuses. *Rejected:* a compiler-side re-derivation
(PolicyCodes drift); lint only (every measured shipped path skipped lint); `job run` left untouched
(RUN: `job run j.loc` with `locale: ja_JP` COMPLETED in ROOT — contract decisions #5); judging
only the target job (a chained job with a typo fails at run with 2810 as today); the CLI not judging
the config keys (5b's OQ8, RUN `s-5b/out/probe-cli-badkey.out`: with `tesseraql.files.timezone:
Asia/Tokio`, `job run nightly` is COMPLETED on HEAD and `FAILED exit 1 TQL-LD-2810 … Unknown
time-zone ID: Asia/Tokio` after 5b — loud, carrying the value, naming neither the key nor "config";
one more `require(configViolations(app, config), System.err::println)` line in `declarationsHold`
closes it — *adjudication, added to 5a with its guard and variant, see the 5a guard table*).

**13 — Three kinds, one mapping, judged only where read.** INVALID → lint ERROR + boot
`TqlException` (1063 / 1041 / 1006); INERT → lint ERROR (1005) + boot WARN, the route served;
ADVISORY → WARNING (1005) on both sides. A value is judged only where the format reads it: a jxls
report's `timezone:` literal, a workbook's `locale:` and `columns[].format`, a `csv` `template:` and
the workbook bound off a workbook are never value-judged — the INERT/ADVISORY line on the key is the
finding — so the list never carries INVALID and INERT for one key. The inert keys: `bom:` off csv;
`sheet:`/`startCell:` off excel; `template:` on csv ("reads no template"); `locale:` on excel
("drives nothing in a workbook"); an unknown export `type:` ("not a column type the export renders
… written as the driver's text"). The advisories: report mode declaring `timezone:` or typed
columns; a `csv`/`pdf` export with `columns:` present, a zone or locale declared and none typed or
formatted (decision 8 of the measurement — blind to a derived column, and the message says so).
*Rejected:* refusing a well-formed inert key (m8/a8 RUN: whole stack down at cold start under `dev`
AND `host`, exit 2, a message naming neither member nor route, a refused hot deploy advancing
`catalog.json`); a fourth kind "invalid-but-inert"; an unknown export `type:` as 1063 (the scope's
letter — RUN by the contract decisions attack: `ColumnValues.format` renders it byte-identically to
an untyped column, so refusing it is the m8 cost; the **import** twin IS 1063, because
`ColumnValues.parse` throws per row).

**14 — Zone rule.** `ZoneId.of(value)` verbatim: region ids case-sensitive (`asia/tokyo` refused),
no `SHORT_IDS` (`JST`/`PST`/`EST` refused — `ZoneId.of("EST")` throws on JDK 25), no trim (`' Asia/Tokyo'`
refused — `ColumnValues.zone` would throw), `Z`, `UTC`, `+09:00`, `UTC+9`, `Etc/GMT-9` pass. Blank
is unset (decision 29). The same rule at the binder: the value the judge accepts is byte for byte
the value the codec receives (5b's V-short-ids and V-trim-judge answer 500 `TQL-LD-2802` for what
the judge passed and the codec refused — red on I2).

**15 — Locale rule: decision 7(ii) with the CLDR alias net — a recorded deviation, open question
U1.** Strict `Locale.Builder.setLanguageTag` parse succeeds, the language subtag is non-empty, AND
(the language is one `Locale.getAvailableLocales()` lists OR the locale's `DateFormatSymbols` /
`DecimalFormatSymbols` differ from `Locale.ROOT`'s). RUN `s-5a/probe/jdk/locale-rule.log` (66 tags,
JDK 25.0.4): the rule matches "the runtime's `Locale.forLanguageTag` does not render as ROOT" with 0
mismatches — `tl`, `sh`, `mo`, `cnr` pass (Marso / mart / martie / март), `und`, `japanese`,
`xx-YY`, `ain`, `tlh`, `cmn`, `x-private` refused (ROOT), `ja_JP`, `de_DE`, `en_US`, `no-NO-NY`,
`ja-JP-JP`, `' ja-JP'`, `C` refused (the parse). Unicode extensions pass
(`ja-JP-u-nu-fullwide` keeps rendering fullwidth digits at 200; `ja-JP-u-ca-japanese` is a valid
declaration). Message: "'X' is not a language tag the JDK can format (expected e.g. en, ja-JP)".
Never `I18nSettings.normalize` (it folds `ja_JP` to `und`); never RFC 4647 lookup (it would fold a
valid `de-CH` claim to `de` and strip extensions). *Rejected:* the language set alone (the letter of
7(ii) — minimal coverage F2 RUN: `tl`, a working declaration the runtime renders as Filipino,
refused at boot with a message that misstates the JDK); the lenient `Locale.forLanguageTag`
(contract decisions #4 — the lenient parser folds `en-`, `de_DE`, a stray space into something the
author did not write).

**16 — The legacy `no-NO-NY` / `ja-JP-JP` / `th-TH-TH` spellings are refused** (the strict parse;
decision 7 left them to the design). Their JDK encodings `nn-NO`, `no-NO-x-lvariant-NY`,
`ja-JP-u-ca-japanese` pass. Cost: a Java-era spelling must be respelled, and "cannot format" is
imprecise for these three. *Rejected:* a pre-map of the three legacy spellings (5b's guard-first Q3
— a second grammar inside the one rule).

**17 — Pattern rule.** `columns[].format` is judged by `type:` where the JDK parser runs — `number`
→ `new DecimalFormat`, `date`/`datetime` → `DateTimeFormatter.ofPattern` — on `csv`, `pdf` and every
import; "neither parser accepts" when `type:` is absent (weaker, said so: it catches `#,##0.00.00`
and `yyyy/MM/dd'`, not `ss.ffff` — MINE probe 2, `patternfacts.log`; `new DecimalFormat("yyyy")` is
valid, hazard 12). **Never on a workbook**: `JxlsFileCodec.writeValue:418-428` / `columnStyles:271-278`
hand the string to fastexcel/POI as the cell format in Excel's vocabulary (`d-mmm-yy`, `0.00E+00` —
RUN by two attackers; 5a's boot IT reads them back from `xl/styles.xml`). Not on a module format. A
blank `format:` is refused as a pattern neither parser accepts. The parsers' own messages (which
echo the whole pattern) are never echoed.

**18 — Cell and column references.** `CellRef.parse` / `ColumnMapping.parseColumn` grammar on
every format (shaping today's raw `IllegalStateException … Not a cell reference`); the workbook
bound (`XFD` / row `1048576`, plus "four letters or more" because `parseColumn` overflows `int` on
seven) on a workbook only (`csv` + `startCell: ZZZZ1` serves today — INERT there).

**19 — Source arms, derived from the `Site`.** A source on a JOB step or a poll job's `import:` →
refused ("a job has no request"; `docs/jobs.md:484`'s "literals only", enforced for the first time).
`request.*` → only `request.locale`, only on `locale:`. `principal.*` → needs the merged
`security.auth` present and not `public` (`ManifestLoader.applySecurityDefaults:447` runs before
either side judges; `AuthStep` is the only `PRINCIPAL` publisher — RUN by two attackers: a route
with no `security:` block has no principal). On `FILE_IMPORT` any `query./params./body.` → refused
(no `RequestBinder` on `buildFileImport:1441-1523`; an import's locale is a literal, `request.locale`
or `principal.<claim>`). On an export route `query./params.` need the first segment declared in
`input:`; `body.` needs it only when `unknownFields: reject` (the default — `RequestBinder:162`
publishes the raw body under `ignore`, RUN by three attackers); an empty segment (`query..tz`) is
refused everywhere; a prefix outside the regex (`path.`, `header.`, `tenant.`) is a literal and
fails the zone/locale rule with the hint "a request source starts with query., params., body.,
principal. or request.locale". *Rejected:* `principal.*` refused only on an explicitly public
route (minimal OQ6, refuted by RUN); `body.` held to declared inputs regardless of policy (three
RUNs).

**20 — The two configuration keys are literals, judged once per manifest.** `tesseraql.files.locale`
/ `timezone` are judged by the same rules with subject `config`; a source expression there → 1063
"an app-wide key has no request" (decision 5(ii): a config key has no `input:` to bind against;
the per-route `principal.claim.zoneinfo` serves the "IdP decides" case; nothing shipped uses it).
Read through `AppConfig.getString` inside `try/catch(TqlException)` on BOTH sides, so an
unresolvable `${…}` placeholder is skipped at lint and at compile (the key's own reader still
fails as today) and `${key:default}` is judged on its resolved value. Judged once per full
`compile()` (never per hot-reloaded route — contract coverage F2 RUN: 15 routes stubbed by one
typo) and once per `RouteReloader.reload()`, refusing the reload whole. Cost: a typo in a key
stalls every further reload until fixed.

**21 — Template existence, where read.** On csv INERT ("reads no template" — m5: 200/COMPLETED
today); on pdf must be `.html` (1006); on excel, pdf and a module format must exist beside the route
or the job file (1006, lint AND boot — the boot twin of lint 1006, measurement decision 10); a name
the file system refuses (a NUL) is 1006 "is not a file path", never an `InvalidPathException`.
`startCell:` without a template is 1041 on excel only. Report mode = excel && `template:` present
and non-blank && no `startCell:`. *Rejected:* judging existence on csv (contract decisions F1: a
refusal for a file nothing opens).

**22 — Format name: case-fold only; `file-export` defaults to csv.** `Excel` → 1063 "format names
are lower-case (excel)" (closes 4c's unfiled #21 lint half); an unknown name is never judged (F82
slice 2 — `FileCodecs.discover()` is classpath-dependent: TQL-YAML-1408 fires on the CLI classpath
and not on the full one; the yaml test classpath carries no codec). `buildFileExport` defaults an
absent `format:` to `csv` at compile with no codec lookup, as `buildQueryExport:1387` does; a job
step's missing `format:` stays the linter's 1041 and the format-dependent arms do not fire.

**23 — Message shape.** `app '<app>': <route 'id' | job 'id' step 'id' | job 'id' | config>
<key>: '<bounded value>' <why>` — every author-controlled fragment (value, column name, derived
input name, template, route/job/step id, app name) through `bounded()`; the parsers' text never
echoed. The lint finding's `line` is the key's line inside the block
(`LintContext.lineWithin(document, block[, entry], token)`), never the document's first `format:`.
`after:` without its statement on a `file-export` is 1041; a `file-export` without an `export:`
block is 1041 `missingBlock` (the block carries `filename:`/`after:`).

**24 — The boot backstop logs every WARN of a block, then throws the first INVALID; the WARN
carries no code.** `require(list, warn)` logs the message text (grep-able); a typed 1005 at the
WARN site would change nothing the operator can act on. Every warning of a route that is then
refused still reaches the log. *Rejected:* throw-first (the operator fixes one line, reboots, meets
the warnings). Disclosed: no fixture carries both an inert key and a refusal with an ordered stderr
assertion (variant SR green — cosmetic).

**25 — `CatalogLocaleRules:57` is narrowed off `excel`** (`equalsIgnoreCase`): TQL-FIELD-4622 no
longer asks a workbook for a locale the same linter refuses — without it an excel export in a
multilingual app has no lint-clean state (three attackers RUN). 4622's removal (it guards nothing on
any export surface — `codes` never reaches an export) stays the lookups record's; it is NOT
extended to jobs.

**26 — `after:` on a query-export stays the compiler's `TQL-ROUTE-3101`; the lint stays silent
on it.** A lint-only 1005 for a boot REFUSAL would be a fourth kind under two codes; the drift is
pre-existing and filed to lint hygiene. RUN: the synthesis is lint-silent and boot-3101 on that
shape, exactly HEAD's (`ExportDeclarationCompileTest.aFollowUpOnAQueryExportKeepsTheCompilersOwnCode`).

### PR 5b — the request, the chain, the job's configuration, the document code

**27 — One altitude judges each provenance; the request judges only what the request supplied.**
`FormatSources.resolve(Exchange, FormatDeclaration)` answers a `Resolved(value, provenance, raw)`
with `Provenance { LITERAL, SOURCE, NEGOTIATED, CONFIG, PLATFORM }`; `RequestFormats.judged` judges
a SOURCE value only. A LITERAL or CONFIG value reaching a binder was judged by 5a's boot twin; the
NEGOTIATED value (`request.locale`) is `LocaleResolution`'s own answer and no caller sent it — never
judged (it can be `und` when the i18n default folds, and then `ColumnValues.locale("und")` is ROOT,
today's bytes). *Rejected:* judging every provenance (V-belt — mislabels an operator's key as the
caller's 400, and RUN turns a route that serves 200 today into a 500 `TQL-ROUTE-5000` on every
request under `tesseraql.i18n.defaultLocale: ja_JP`: I14 on V-belt and V-reqloc-judged); curing
that by having 5a refuse the `und`-folding default so the negotiated tag can be judged (guard-first
coverage #47 — the negotiated tag is the framework's whatever 5a does; the folding default IS a
config defect and is filed). Evidence: `s-5b/out/MATRIX.txt` U1/U14/U15/I8c/I14.

**28 — The chain is one rule, per key, on every arm: literal → source → config → platform.** For
`locale` and `timezone` independently: (1) the route's (or step's) literal when declared and not
blank; (2) else the request source when it resolves to a non-null, non-blank value — judged, or
NEGOTIATED and passed through; (3) else the configured literal `tesseraql.files.<key>` — never
judged here, never resolved as a source (`FormatDeclaration(null, "query.tz")` answers `query.tz`
with provenance CONFIG — V-cfgresolve resolves the operator's key and blames a bad value on the
caller); (4) else null, the platform default. The compiler carries `FormatDeclaration(key,
declared, configDefault)` — the route's declaration AND the app-wide literal — to the three
binders (`QueryExportBinder`, `FileExportStartProcessor`, `FileImportProcessor`); collapsing them at
compile time (HEAD's `formatDeclaration`) is what made an unresolved source skip the configuration.
The job arms walk rungs 1, 3, 4: `ExportStepRunner` through `context.fileDefaults().localeOr /
timezoneOr`, and the poll-triggered import through `PollSources` (the shared schema promises the
fallback on both; RUN I15: HEAD moves the file to `.error` and parses nothing). Eight arm × key
cells, each with a SOURCE-applied and a CONFIG-applied assertion (the coverage rule the contract
attacker stated; five green-on-defect variants were holes in that grid). *Rejected:* source-else-JVM
(decision 5(a), HEAD, pinned by the renamed test); splitting the shared schema `$ref` (3(b));
wiring the export step but not the poll import.

**29 — Blank is unset at every rung** — `?tz=` → rung 3; `timezone: ''` → rung 3 (today's
compile-time rule kept); `tesseraql.files.timezone: ''` → rung 4. This reads decision 7's "blank =
platform default" as "blank is unset" — HEAD's own reading for a blank literal, which
`formatDeclaration` already sends to the key. *Rejected:* "blank skips the key" (`?tz=` would bypass
the operator's key while `timezone: ''` does not).

**30 — A principal-sourced LOCALE spelled with an underscore is read in its dash form — a
recorded deviation from decision 7's "one rule", open question U2.** `en_US` → `en-US`, `ja_JP` →
`ja-JP`, `de_DE` → `de-DE`, when and only when the dash form passes the strict rule; `en_US.UTF-8`
is refused with the original spelling quoted; a `query.`/`body.` value keeps the strict rule
(`?loc=ja_JP` → 400; the caller can retype an input, the operator cannot retype Okta); zone claims
have no such clause. RUN on all three 5b designs by their attackers and in I6b/I8/V-nofold: OpenID
Connect Core 1.0 §5.1 sanctions `en_US` as a compatibility spelling (Okta's `locale` attribute
defaults to it); `LocaleResolution.negotiate` already tolerates the same claim by falling through;
without the fold every export and every upload for every user of such an IdP becomes a 400 the
user cannot correct, where it is a 200 today, and the operator sees it only at DEBUG.

**31 — A non-text claim or input is refused as not text.** A claim or `body.*` value that is a
JSON number, boolean, array or object is refused before the predicate sees it (`IdP claim
'zoneinfo' is not a text value but an array, so it cannot name a time zone`) — V-stringify judges
`'[Asia/Tokyo]'` as a zone and blames a string nobody sent.

**32 — The envelope and the log line.** Status 400, code `TQL-FIELD-2001`,
`details.fields[{field, code, source, messageKey, message}]`: `field` = the declared input's name
(`query.tz` → `tz`, `body.report.tz` → `report.tz`) or the whole expression for a claim
(`principal.claim.zoneinfo` — no input declares it, and the path says it is not the caller's form);
`code` = `timezone` | `locale`; `source` = the declaration; the message key `tql.input.<code>` for
an input and `tql.input.claim.<code>` for a claim (the claim text says the value is the sign-in
profile's and whom to ask — a browser user must not read "expected e.g. Asia/Tokyo" under a field no
form has); **no `value` on the wire** (the `InputBinder` precedent carries constraint params, not
echoes; the caller knows what it sent). One entry per refusal, the locale judged first. Minted in
ONE place: `InputBinder.reject` made package-private with a five-argument overload taking the
message key. Nothing is logged at INFO or above (a 4xx is DEBUG in `PipelineRunner.logFailure:186-195`;
the DEBUG line carries the bounded value). No `+09:00` in the catalog hint (`+` is a space in a query
string — RUN: `?tz=+09:00` → 400 with `" 09:00"`, `%2B09:00` → 200). *Rejected:* a `value` on the
wire (V-value); a WARN per refused claim (decision 4(b)'s own cost transposed onto (a); I9 pins
"nothing above DEBUG" — V-warn-quiet red); the generic input text for a claim (V-claim-generic-key).

**33 — A failure while writing the document is `TQL-LD-2802`, filed by PHASE.** Inside
`SqlStep.export`'s reader lambda the `ExportWrite.write(...)` call is wrapped: a `TqlException`
passes through (the row cap 2850, the split key 2858, the row set's own errors); an `IOException` or
`RuntimeException` raised INSIDE the write — the codec, `ColumnValues.format`, the enricher, the
spool the codec writes to — becomes a marker `DocumentWriteFailure`, which the catch-all files as
`TQL-LD-2802 "Writing the <format> document failed after the query ran: <cause>" [<export
filename>]` (`.source(filename)`, never the SQL file's path). A statement failure keeps `TQL-SQL-2500`
(I11); an `UncheckedIOException` raised OUTSIDE the write (`createWriter`, the writer's close,
`openInput`) keeps 2500 at `:348-349`. `ErrorResponseRenderer.httpStatus` needs no arm (LD → 500).
The async arm (`exit_message`) and the job arm (2810) do not run through `SqlStep.export`.
*Rejected:* keeping the `UncheckedIOException` arm for the spool (contract OQ5 / guard-first — RUN
I16: a spool cap hit twenty rows into the write is `TQL-SQL-2500 … Spool exceeds
tesseraql.temp.maxBytes … [export.sql]`, sending the operator to a SQL file that ran to
completion); classifying by checked-ness; a 400 (V-400 — a 400 is DEBUG, so the presence control
finds no ERROR line).

**34 — `FileDefaults` is a small lazy reader beside `SqlDefaults`; `JobExecutor.fileDefaults(…)`
is wired at both executor sites.** `FileDefaults.of(AppConfig)` reads `tesseraql.files.locale /
timezone` **on use**, not at construction (`AppConfig.getString` throws `TQL-YAML-1101` on an
unresolvable placeholder — an eager read would refuse the boot of an app that has no file recipe
and boots today; I17 red on V-eager-config). `JobExecutor` gains the optional setter after
`fileTransfers` and before `filePush`'s javadoc (never between a javadoc and its method — the
reactor's `-Xlint:all -Werror` refuses a dangling doc comment); `StepContext.Collaborators` gains
the component (a third construction site is then a compile error); `TesseraqlRuntime:1185` and
`JobCommand.java:435` wire it; `PollSources` takes it by constructor. *Rejected:* four inline
`getString` calls (six literal spellings across three modules and no home for the lazy rule).

**35 — `FormatSourcesTest.unresolvableExpressionsYieldNullForThePlatformDefault` is renamed,
never deleted:** `anUnresolvableExpressionAnswersNullSoTheBinderFallsToTheConfiguredLiteral`, its
assertions unchanged (the pure overload still answers null), its javadoc saying null is rung 2
saying nothing and `RequestFormatsTest` holds rungs 3 and 4.

**36 — The judge stays in the three binders; an `http:` source declared on the route is fetched
before it.** `httpSourcesFirst` (`RouteCompiler:1426`, `:1576`) precedes the export binder, so a
request refused for a value the `RequestBinder` already held spent one outbound call (RUN by the
contract coverage attacker: partner hits 0 → 1). The contract says "before any SQL" and nothing
broader. *Rejected:* a new pipeline `Step` after the `RequestBinder` publishing both values through
exchange properties — a route-compiler behaviour change for a rare route shape, and the
file-export judge cannot move without splitting `startExport`.

### PR 5c — `splitBy:` takes effect

**37 — `{key}` is never the job context's placeholder.** `StepContext.interpolate` passes the
literal `{key}` through on every template it renders (an export step's `filename:`, a push step's
`as:`), checked BEFORE `resolve`; `{batch.businessDate}` still renders, an unresolved dotted path
still renders empty. *Rejected:* `ExportStepRunner` re-inserting `{key}` after interpolation (a
sentinel swap around the call); exempting `{key}` only when the step declares `splitBy:` (a flag
through the one seam, and a job without `splitBy:` erasing what a route leaves literal); leaving
every unresolved placeholder literal (v-f — `{typo}` in a delivered filename is a new failure shape;
red on G5). Cost: a push `as: x-{key}.zip` delivers `x-{key}.zip` where today it delivers `x-.zip`
(RUN) — visible; a lint WARNING for `{key}` in `push.as:` is filed. The resolve-first spelling (v-q)
is behaviourally identical today (the job context's top-level keys are `params`, `steps`, `tenant`,
`batch` — `JobExecutor:329-339`) and recorded so a future top-level key does not change which
spelling is right.

**38 — A split transfer is recorded as what it delivers, at the row; `download()` types by the
recorded format.** At `startExport` and `exportInline` a spec with `splits()` records `format =
zip` (`SplitExport.BUNDLE_FORMAT`) and `filename = SplitExport.zipName(declared)`; the
per-document pattern still goes to `ExportWrite.write` for the entries (v-m hands the writer the
bundle and FAILS 2858); `InlineResult.filename` is the recorded name, so `steps.<id>.filename` is the
bundle's; the status JSON's `filename` is `orders.zip`; `recent()` reads `zip` / `orders.zip` (the
ops console's cell). `download()`: `zip` → `application/zip`; anything else →
`codecs.require(format).contentType()` as today, looked up only on that arm (v-l keeps the lookup
first and answers 500 `TQL-LD-2801` on every split download). A csv an author named `notes.zip`
whose body begins with `PK` stays `text/csv` under its own name: the type follows the bytes'
producer, the name follows the author (slice 4's charter). *Rejected:* deriving the bundle at read
time from a `{key}` in the recorded name (v-h — the row and the console say `csv` /
`orders-{key}.csv`); returning the pattern from `exportInline` (v-g/v-p); the `.zip`-suffix rule
(v-e); the magic-bytes rule (v-n); a new `content_type` column (three vendor DDLs under
`VendorMigrationSetTest`, an `ensureSchema` line, and an H2 hazard with no guard: the standalone
runtime runs Flyway before `ensureSchema`, and no H2 test exports a file).

**39 — `zipName` lives in core beside the placeholder it strips; `FileWriteSpec.splits()` is the
one split predicate; blank is no split.** `SplitExport.zipName` (moved verbatim from
`SqlStep:434-446`, public; `SqlStepZipNameTest` moves with it as `SplitExportZipNameTest`), the
constants `BUNDLE_FORMAT` and `BUNDLE_CONTENT_TYPE`; `splits()` = `splitBy != null &&
!splitBy.isBlank()` replaces four hand-spelled copies (`ExportWrite:42`, `SqlStep:362`, the two
transfer sites); `ExportRules.java:210` (yaml, reads the YAML record) stays. `splitBy: ""` is
lint-clean, schema-valid and a plain export today (RUN: COMPLETED, `text/csv`) — pinned by
`FileWriteSpecTest` and a blank-`splitBy:` route (v-k treats blank as a split: FAILED 2858; v-k2
re-spells the predicate at a transfer site: csv bytes served as `application/zip`). A duplicated
(not moved) `zipName` is green on every guard — the checklist's `git grep zipName` is the only
catch.

**40 — Nothing is judged at lint or boot by 5c; zero groups still complete; a split that fails
before bundling carries the bundle's name.** `TQL-YAML-1041` (`splitBy:` without `{key}`) already
fires; 2857/2858 keep their sites; no new code, key or schema field (`GeneratedReferenceTest` 10/10,
the regeneration diff empty). Zero groups produce an empty ZIP that COMPLETES on both surfaces (on a
job this is new only because the job could not run before); the job guard therefore runs over two
groups and asserts the entry names (hazard 18: v-d COMPLETES the zero-group job and FAILS the
two-group one with 2857). A split transfer that fails before bundling (no `filename:` in an
un-linted app) records `zip` / `<stem>.zip` on its FAILED row — cosmetic, recorded, unguarded.

### Docs, CHANGELOG, the audit record

**41 — Each published sentence is written by exactly one PR, and the shared bullet is composed
once, here.** `docs/file-transfers.md`'s `locale:`/`timezone:` bullet is rewritten by 5a in its
final shape except the chain sentence, which 5b replaces (the 5b synthesis's replacement text,
written against HEAD, would have erased 5a's sentences — corrected below). The shared schema
description (`tesseraql-defs-v1.schema.json:118-125`, both copies) is edited ONCE, by 5a, in a
form that is true after 5b and no falser than HEAD between the two (HEAD already promises "unset,
`tesseraql.files.locale` applies"); 5b does not touch the schema, so only 5a carries
`tesseraql-maven-plugin`, the scaffold regeneration and the demo-pom drift revert. The 2810 row of
the error-code table is rewritten by 5b only (as the catch-all it is — `JdbcFileTransferService:214`
"Failed to create file transfer schema" is a live raise; 5a's base text dropped a live meaning).
`docs/jobs.md:482-484` is edited by all three of 5a, 5b, 5c — each re-anchors on the merged text.

**42 — CHANGELOG: one entry set per pull request, inside its measured boundary, no migration
steps.** 5-0 two `Fixed`; 5a one `Added` + one `Fixed`; 5b one `Changed` (the `JobExecutor` API)
+ four `Fixed`; 5c one `Fixed`. A breaking change (DuckDB `time` text, the split transfer's
recorded name, `TQL-LD-2802` where clients saw `TQL-SQL-2500`) records what changed and why —
pre-1.0, no upgrade notes.

**43 — The audit record is amended once with the facts, and once per pull request with the
status.** 5-0 carries every fact amendment of MEASUREMENT §2.6 (`docs/audit-medium-leads.md:30`
lead text, `:120-121`, `:152-155`, `:156-162`, `:181`, `:213`, `:277`, a new item in "Defects
surfaced that the audit does not carry"; `docs/download-name-and-bytes.md` decision 15 `:300-316`,
the export-hygiene list `:880-888`, the slice-5 line `:889`) plus its own status; 5a, 5b and 5c
append their status to `:30` and `:277` only, and 5c appends the "moved to core" note to
`download-name-and-bytes.md` decision 6 (`:176-181`). *Rejected:* each PR rewriting the rows it
touches (5a's synthesis assumed it carried the facts — three PRs rewriting the same paragraph).

---

## The contracts and the guards, per pull request

Hazard numbers are MEASUREMENT §8's (1-35); **36** is 5b's addition (a "sequence unchanged" guard
reading `last_value` alone is green on a codec-placed refusal on a fresh sequence — read the pair
`(last_value, is_called)` and prime the sequence). Every guard below is RUN red on the named built
variant(s) and green on the fix, in one bracketed run whose build stamps are quoted.

### PR 5-0 — the codec renders every value the driver hands it

**Change list (`44bc299fe`).** `tesseraql-core/.../files/ColumnValues.java`: `:9-10` imports
`LocalTime`, `OffsetTime`; `:117-133` `format()` gains the time arm after the Number arm (`column.format()
!= null ? DateTimeFormatter.ofPattern(column.format(), locale).format(time) :
DateTimeFormatter.ISO_LOCAL_TIME.format(time)`); `:137` the new `public static LocalTime
toLocalTime(Object)` with the comment naming A1 as the defect ("never decode `getTime()` as seconds
since midnight UTC"); `:139-142` `toZoned` javadoc + `case null -> null; case java.sql.Time _ ->
null;` before `case java.sql.Date`. `tesseraql-excel/.../JxlsFileCodec.java`: `:413-418`
`writeValue` — the time arm first (`sheet.value(rowIndex, colIndex, dayFraction(time))`, style
format `hh:mm:ss` or the column's); `:443-446` `setCell` — the time arm first
(`cell.setCellValue(dayFraction(time))`), javadoc "a declared format wins over the prototype
style"; `:457` `private static double dayFraction(LocalTime)` = `toNanoOfDay() / 86_400e9`.
`CsvFileCodec`/`PdfFileCodec`: no change (both render through `ColumnValues.format`). Tests:
`ColumnValuesTest` +6 at `:63`; `JxlsFileCodecTest` +7 methods and 6 helpers before `:220`,
`writePlacementTemplate:300-302` gains a third bordered prototype cell (F5); `CsvFileCodecTest` +1
at `:180`; `PdfFileCodecTest` +1 at `:123`; NEW `tesseraql-runtime/.../ExportTimeAndNullCellIntegrationTest`
(8 methods, one Testcontainers boot); `InternalDocsSyncTest:49` +1 assertion. Docs: the record, the
two registrations, `file-transfers.md` one bullet, the audit-record facts, CHANGELOG.

**Guards.**

| module · class · method | fixture | assertion | red on (UTC / JST) | hazards |
|---|---|---|---|---|
| core · `ColumnValuesTest.aNullValueIsNotATemporal` | `null`, untyped column, Tokyo | `toZoned` isNull, `toLocalTime` isNull, `format` isNull | HEAD, V1, V2, V3, V4, V7, A3 | — |
| core · `aSqlTimeRendersAsWallClockText` | `Time.valueOf("22:30:00")` untyped / `HH:mm` / `type: datetime` / `type: date` (+`HH:mm`) | `22:30:00`, `22:30`, `22:30:00`, `22:30:00`, `22:30`; `toZoned(time)` isNull, no throw | HEAD, V1, V3, V4, A7, Z1, A2 (UTC) / A1 (JST) | 24 |
| core · `aLocalOrOffsetTimeRendersLikeASqlTime` | `LocalTime 22:30`; `OffsetTime 22:30-05:00` under Tokyo; `22:30+09:00` under UTC; `22:30:05` + `HH:mm`; `22:30:00.5` ×2 | `22:30:00` ×3; `22:30`; `22:30:00.5` ×2 | HEAD, V1, V3, V4, V8, V9, A4, K1 | the offset MUST differ from the export zone; the ISO fixture MUST have zero seconds |
| core · `aSqlTimeKeepsItsWallClockUnderEveryJvmAndExportZone` | `TimeZone.setDefault` (try/finally) Tokyo, New York, UTC × export UTC, Tokyo, Auckland | `22:30:00` for all nine | HEAD, V1, V3, V4, **A1, A2 (both zones)** | 1, 3 |
| core · `aColumnFormatOnATimeUsesTheExportLocale` | `hh:mm a` under `Locale.JAPAN` AND `Locale.US` | `10:30 午後`; `10:30 PM` | HEAD, V1, V4, **A6** (both) | a no-locale arm is red on any JVM locale |
| core · `aSqlTimeWithMillisecondsRendersWithoutThem` | `new Time(… + 500)` | `22:30:00` | HEAD, V1, V3, V4, **V10** | pgjdbc `time(3)` hands exactly this |
| excel · `JxlsFileCodecTest.aGridWritesANullCellBlankAndKeepsGoing` | grid, row 2 every mapped cell NULL (text, typed number, typed datetime), row 3 `gamma` | row 1 typed control; row 2 blank; row 3 `gamma` | HEAD, V2 | 23, NULL-never-written, `Map.of` |
| excel · `aPlacementWritesANullCellBlankAndKeepsGoing` | placement B5, columns B/D/F | row 6 blank; row 7 `gamma` | HEAD, V2 | 23 |
| excel · `aJxlsReportRendersANullCellAsEmptyControl` | jxls template, `qty: null` | blank | **none — the control, green on all 24 columns** | 23 |
| excel · `aGridWritesASqlTimeAsATimeCell` | `Time`, `Time`+`hh:mm`, `OffsetTime -05:00`, `LocalTime` | NUMERIC `0.9375`, date-formatted, `hh:mm:ss` / `hh:mm`; `0.9375` not `0.5208` | HEAD, V1, V3, V4, V5, V6, V8, V9, K2, A2 (UTC) / A1 (JST) | 24 |
| excel · `aPlacementWritesASqlTimeAsATimeCell` | D5 no format, F5 `hh:mm`, H5 `OffsetTime` | D5 `0.9375` `General` border THIN; F5 `hh:mm`; H5 `0.9375` | HEAD, V1, V3, V4, V5, V8, V9, **K3**, A2 (UTC) / A1 (JST) | 24 |
| excel · `aGridTimeCellKeepsItsWallClockUnderEveryJvmAndExportZone` | the flip × export UTC, Tokyo | B2 `0.9375` ×6 | HEAD, V1, V3, V4, V5, **A1, A2 (both)** | 1, 3 |
| excel · `aGridTimeCellKeepsAFractionOfASecond` | `LocalTime 22:30:00.5` | within `1e-12` of `0.9375 + 0.5/86400` | HEAD, V1, V3, V4, V5, V8, **A5** | — |
| operations · `CsvFileCodecTest.writeRendersASqlTimeAsWallClockText` | untyped `Time`, `hh:mm a` under `ja-JP` | `late,22:30:00,11:45 午後\r\n` | HEAD, V1, V4, **A6**, A2 (UTC) / A1 (JST) | 24, 30 |
| pdf · `PdfFileCodecTest.gridExportRendersASqlTimeAsWallClockText` | same row, `HH:mm` | text has `22:30:00`, `23:45`, not `23:45:00` | HEAD, V1, V4, A2 (UTC) / A1 (JST) | 24 |
| runtime · IT `aTimeColumnExportsAsWallClockTextOnCsv` | `shifts(time)` with a NULL row | 200; `late,22:30:00\r\n,\r\nearly,06:15:00\r\n` | HEAD (500), V1, A1 (JST) | 24 |
| runtime · IT `aTimeColumnKeepsItsWallClockOnAJvmWhoseZoneIsNotUtc` | `TimeZone.setDefault(Asia/Tokyo)` in try/finally; csv undeclared + grid declaring `timezone: UTC` | csv `late,22:30:00`; grid B2 `0.9375` | HEAD, V1, V3, V5, **A1 (`late,13:30:00`), A2 (`0.5625`) — both hosts** | 1, 3 (pgjdbc follows `setDefault` per call); without the grid's `timezone: UTC` the IT is A2-blind (RUN 8/8 green) |
| runtime · IT `aTimeColumnAndANullRowExportOnAnExcelGrid` | four declared columns, all NULL on row 2 | B2 `0.9375` date-formatted; row 2 blank; A4 `early` | HEAD, V1, V3 (`25569.9375`), V5 (STRING) | 23 |
| runtime · IT `aTimeColumnAndANullRowExportInPlacementMode` | `frame.xlsx` + `startCell: B5` | B5 `late`, D5 `0.9375`; row 6 blank; B7 `early` | HEAD, V1, V3, V5 | 23 |
| runtime · IT `aTimeColumnExportsAsWallClockTextOnPdf` | pdf grid | text has `late`, `22:30:00` | HEAD, V1 | 24 |
| runtime · IT `aFileExportExcelGridWithANullRowAndATimeCompletes` | file-export excel grid | 202 → COMPLETED → `/file` B2 `0.9375`, row 3 blank | HEAD (FAILED rowCount 0), V1, V3, V5 | 7 |
| runtime · IT `aSplitExcelGridCarriesTheTimeThroughTheSpool` | `splitBy: label`, two labelled rows | two ZIP entries; the `late` entry's B2 `0.9375` | HEAD, V1, V3, V5 | 4 (two groups: the spool and the zone are exercised) |
| runtime · IT `aJobExportStepOverATimeColumnCompletes` | job `shifts.export`, csv step | `runJob(…).status() == COMPLETED` | HEAD (`TQL-LD-2810 … null`), V1 | 19 |
| docs-reference · `InternalDocsSyncTest:49` | — | `ErrorIndex.isInternalDoc("export-declarations.md")` | nav-only, index-only, neither (the new assertion alone catches "neither") | 32 |

**Variants (24 columns).** m2 (the `.m2` jars), head, V1 null-only, V2 time-only, V3 time-epoch,
V4 catch-uoe, V5 excel-string, V6 no-default-time-format, V7 writers-null-first, V8
passthrough-localtime, V9 offset-shift, V10 time-millis, A1 time-utc-epoch, A2 time-export-zone, A3
localtime-no-null, A4 iso-no-fraction, A5 excel-seconds-only, A6 format-unlocalized, A7
type-date-passthrough, K1 offset-to-export-zone, K2 grid-shifts-offset-time, K3
placement-shifts-offset-time, Z1 tozoned-no-time-arm, fix.

**Bracket (RUN).** `SYNTH BRACKET 2026-09-12T15:31:36Z zone=UTC` and `15:33:04Z zone=Asia/Tokyo`;
HEAD `44bc299fe`; core jar `11:18:47`, excel jar `11:18:58`; every column compiled fresh inside the
run (sha256 + build time per column; fix `cbdc4464c1ae`, guards `084c355500db`); every run prints
the code source of `ColumnValues` and `JxlsFileCodec`. 15 guards × 24 columns × 2 zones. Red guards
per column (UTC / JST): m2 14/14, head 14/14, V1 12/12, V2 3/3, V3 9/9, V4 12/12, V5 4/4, V6 1/1,
V7 1/1, V8 4/4, V9 3/3, V10 1/1, **A1 2/9, A2 8/4**, A3 1/1, A4 1/1, A5 1/1, A6 2/2, A7 1/1, K1 1/1,
K2 1/1, K3 1/1, Z1 1/1, **fix 0/0**; the control green on all 48 cells. IT (`15:35:23Z` UTC,
`15:36:16Z` JST; postgres:16-alpine, port 0, every container reaped): m2 0/8 both zones, V1 0/8,
V3 3/8, V5 3/8, A1 7/8 UTC (the flip method) and 1/8 JST, A2 7/8 UTC and 6/8 JST, **fix 8/8 both**.
Maven (`MVN STAMP 15:34:38Z`): `./mvnw -o -B -ntp spotless:apply` then `verify -pl
core,excel,operations,pdf` with the guards merged into the four existing test classes — BUILD
SUCCESS (enforcer, PMD 7.27, spotless, `-Xlint -Werror` + doclint; surefire 446/14/109/14, 0
failures; spotless reflowed nothing). Docs guards (`DOCS STAMP 15:24:09Z`): sync-content 69/99
with the record stub, lint-prose 0 violations and RED on a planted 61-word control;
`InternalDocsSyncTest` four-column bracket green/red/red/red. Logs: `s-5-0/logs/`.

**Uncovered, disclosed.** `format: ''` on a time (renders an empty cell on csv/pdf, the pre-existing
date-time shape — 5a refuses a blank pattern); a pdf-side `OffsetTime` shift (unreachable: pdf spools
→ 2853); jxls report mode's `25569.9375` for a `Time` (export hygiene); removing `timezone: UTC` from
the IT's grid route silently returns it to A2-blind (the route comment says why it is there); V9 is
kept as a column but K1/K2/K3 carry decision 9's proof — a future fixture edit must keep the offset
≠ the export zone.

### PR 5a — every literal export declaration is judged once, at lint and at compile, with one code

**Change list (`44bc299fe`).** NEW `tesseraql-yaml/src/main/java/io/tesseraql/yaml/app/ExportDeclarations.java`
(decision 2; ~770 lines after spotless; plus the two `Optional<String>` extractions of the
adjudication). `lint/ExportRules.java:20-26` typed constants (`UNUSABLE_EXPORT_TEMPLATE` deleted —
PMD's unused gate), `:47-49` `lintExportStep(context, config, job, step, …)`, `:70-99` the bom /
sheet-startCell / `.html` / template-existence arms replaced by `report(context, source, "export:",
violations(Site.step(…), export, dir), …)`, the `startCell:`-without-template 1041 narrowed to
excel, `:121-153` `lintRouteExport(context, config, route, definition, …)` (missing block → 1041;
excel-only 1041; `report(...)`), `:164-173` `lintByteOrderMark` deleted (the INERT arm carries the
text); NEW `report(...)` mapping INVALID/INERT → ERROR, ADVISORY → WARNING with
`context.lineWithin(document, block[, entry], token)`; `lint/LintContext.java:87` gains
`lineWithin`; `lint/RouteRules.java:182` the `import:` arm; `lint/JobRules.java:166`, `:316-321`
the poll-job `import:` arm; NEW `lint/FilesConfigRules.java` (20 lines) registered at
`AppLinter.java:73` after `CatalogLocaleRules`; `lint/CatalogLocaleRules.java:57` narrowed.
`tesseraql-compiler/.../RouteCompiler.java:41` `LOG`; `:138` `if (onlyRouteIds == null)
require(configViolations(appName, config), LOG::warn)`; `:1387` `requireValidExport(definition,
spec, routeDir)` before `require(format)`; `:1447` the import arm; `:1548-1557` `spec == null` →
1041, `requireValidExport`, `format = blank ? "csv" : spec.format()` at `:1578/:1583`;
`binding/FormatSources.java:55-57` delegates to `ExportDeclarations.isSourceExpression`.
`tesseraql-runtime/.../TesseraqlRuntime.java:1346-1351`, `:1434` `requireValidJobDeclarations(appName,
job)` before each put; `RouteReloader.java:137` the reload judge. `tesseraql-cli/.../JobCommand.java:148-176`
(`run`) and `:197-215` (`rerun`) → `declarationsHold(manifest, jobs)`: `requireJob` over every job
AND `require(configViolations(app, config), System.err::println)`, a `TqlException` printed as one
line, `return 2`. Schema `:118-125` both copies (decision 41's final text, §Docs). Docs and
references (§Docs). Tests: `ExportDeclarationsTest` (24 methods / 73 cases), `AppLinterRouteExportTest`
(+9, 33), `AppLinterExportStepTest` (+5), `AppLinterImportLocaleTest` (+2), `AppLinterFilesConfigTest`
(new, 7), `AppLinterCatalogLocaleTest` (+1), `AttackGuardsTest` (6), `ExportDeclarationCompileTest`
(22), `AttackCompileGuardsTest` (8), `ExportDeclarationBootTest` (6, Testcontainers),
`AttackBootGuardsTest` (3, with the test-scoped `AttackMountedAppSourceProvider`),
`JobCommandIntegrationTest` (+2 with the adjudication's config-key case).

**Guards, by class** (the 102-row guard × variant delta table is `s-5a/logs/matrix.md`, embedded in
`synth-5a.md` §4.3; every refusal predicate asserts code 1063 AND `isError()` AND the route/step id
AND the key AND the quoted value):

| class | what it pins | red on |
|---|---|---|
| yaml · `ExportDeclarationsTest` | the predicate: zone (`' Asia/Tokyo'`, `JST`, `asia/tokyo` refused); locale (`tl`/`sh`/`mo`/`cnr`, `nn-NO`, `en-GB-oed` pass; `tlh`, `i-klingon`, `' ja-JP'` refused); Excel cell formats ×7 not judged on excel, refused on csv; unknown `type:` INERT on export / INVALID on import; `ZZZZZZZ` (int overflow) and `A1048577`; the bound not judged off a workbook; `body.` held to inputs only under `reject`; a step without `format:` judged on its values only; import sources by surface; template missing/`.html`/NUL/module; report-mode `Asia/Tokio` an advisory; `after.sql: {mode: update}` incomplete on file-export only; `bounded` (U+2028/2029/202E/NUL, the surrogate cut, a lone surrogate); every fragment bounded (a 5,000-char LF+NUL value in nine positions → every message < 500 chars) | V3, V4, V6, V8, V9, X2, X4-X6, X8, X10, SA, SB, SC, SF, SH, SI, SJ, SK, SO, SP |
| yaml · `AppLinterRouteExportTest` | the route arm end to end: each refusal names `route 'items.dump'`; Excel formats not parsed; `body.` under `ignore`; `after:` on query-export left to 3101; csv `template:` is 1005 not 1006; a report-template zone is the advisory; the finding's line is the key's inside the block; no finding carries an unbounded value | V1, V3, V6, V8, V9, X2, X8, X9, X10, SA, SB, SH, SI, SJ, SK, SO, SQ |
| yaml · `AppLinterExportStepTest` | the step arm; a source on a step refused; the message says `step 'report'` | V1, V2, X9 |
| yaml · `AppLinterImportLocaleTest` | route and poll-job `import.locale`; `principal.*` on a poll job refused; import columns/type refused | X4, X5, SC, SM |
| yaml · `AppLinterFilesConfigTest` | the config keys: a source refused; `${UNSET}` skipped; `${X:Asia/Tokio}` judged on its default; locale beside zone | V7, X1, SS |
| yaml · `AppLinterCatalogLocaleTest.aWorkbookExportIsNotAskedForALocaleItNeverReads` | excel / `Excel` / excel+`locale: en` never draw 4622 | SN |
| yaml · `AttackGuardsTest` (the guard-sensitivity attacker's six) | the corruptions X1-X10 | X1-X10 |
| compiler · `ExportDeclarationCompileTest` | the boot twin on `format: csv` fixtures (and `format: excel` on file-export, which looks no codec up): class AND code AND route id; the `format: pdf` fixture pins "before `require(format)`" (V5); `ZZZZ1` refused on a workbook, csv twin compiles with a WARN; unknown export type warns and compiles; a config key judged once per manifest (`compile(…, Set.of(id))` with a bad key compiles; the full compile refuses); a 5,000-char LF template → a message < 400 chars | V1, V3, V5, V6, V9, X2, X3, X6, SA, SB, SD, SH, SI, SK, SO, ST |
| compiler · `AttackCompileGuardsTest` (8) | `anUnresolvedPlaceholder…StillCompiles`; the missing template on excel with the csv twin compiling; `anAbsentSecurityBlockIsPublic` | X2, X3, ST, D4 |
| compiler · `ErrorCodeUniquenessTest` | 1005/1041 spelled once | SU (String twins) |
| runtime · `ExportDeclarationBootTest` (Testcontainers, port 0) | a bad step literal refuses boot naming app + job#step + key; the valid twin boots AND `runJob(id).status() == COMPLETED`; an inert step key WARNs (exact line, absent on the twin) and the job runs, with `0.00E+00`/`d-mmm-yy` read back from `xl/styles.xml`; a mistyped config key refuses a hot reload whole and the routes keep serving (boot, GET 200, append `Asia/Tokio`, `reloader.reload()` throws 1063, GET 200, fix, reload succeeds); a mistyped poll-job `import.locale` refuses boot | V2, V7, V10, X7, X16, SA, SD, SE, SM |
| runtime · `AttackBootGuardsTest` (3) | the mounted-app job arm (`AttackMountedAppSourceProvider`), its COMPLETED twin, the job-step missing template | V10, X7 |
| cli · `JobCommandIntegrationTest.aMistypedExportStepLiteralIsRefusedBeforeAnyExecutionRowExists` | `job run report.daily` with `locale: ja_JP` → exit 2, stderr `TQL-YAML-1063 app 'demo' job 'report.daily' step 'report' export.locale 'ja_JP'`; no `tql_job_execution` row (the table is not created); `job list` exits 0; the corrected twin COMPLETED | SL |
| cli · `JobCommandIntegrationTest.aMistypedFilesConfigKeyIsRefusedBeforeAnyExecutionRowExists` (**adjudication**) | `tesseraql.files.timezone: Asia/Tokio` in the app config → `job run report.daily` exits 2, stderr names `config`, `tesseraql.files.timezone`, `'Asia/Tokio'`; no execution row; the twin with a valid key COMPLETED | SL2 (`declarationsHold` without the config call — to be built and proven red before the PR ships: without it the run is `FAILED exit 1 TQL-LD-2810 … Unknown time-zone ID: Asia/Tokio` after 5b, `s-5b/out/probe-cli-badkey.out`) |

Hazard mapping: 10, 12, 13, 14, 15, 16, 17, 19, 21, 22, 27, 29, 32, 33.

**Variants (40 + SL2).** V1 lint-only · V2 no-job-arm · V3 forLanguageTag · V4 short-ids · V5
after-require · V6 no-bound · V7 no-config-at-boot · V8 no-untyped-pattern · V9 principal-unchecked ·
V10 no-mounted-job-arm · X1 config-loop-timezone-only · X2 null-security-authenticated · X3
no-template-at-boot · X4 import-columns-unjudged · X5 import-format-unjudged · X6
after-arm-sql-null-only · X7 job-arm-no-template-dir · X8 zone-strip · X9 lint-inert-as-warning ·
X10 locale-strip · X16 advisory-refuses-boot · SA excel-pattern-parsed · SB body-always-declared · SC
import-route-binds-request · SD config-per-route-compile · SE no-reload-judge · SF
locale-language-set-only · SH after-on-query-export · SI template-existence-on-csv · SJ
report-zone-judged · SK unknown-type-refused-on-export · SL no-cli-job-arm · SM no-poll-import-arm ·
SN 4622-not-narrowed · SO unbounded-fragments · SP template-path-uncaught · SQ line-document-wide ·
SS config-raw-node-at-lint · ST placeholder-refused-at-compile · SR throw-before-warn · SU
String-constant twins (by hand) · **SL2 no-cli-config-arm (adjudication; not yet built)**.

**Bracket (RUN).** `s-5a/bracket.py`, 2026-09-12 16:02:17–16:15:20 UTC on the spotless-formatted
tree (anchors verified ×1 after spotless), verdicts read from surefire XML with a run-start stamp
check (no STALE cell). FIX: **0 RED of 186** (yaml 145, compiler 30, runtime 9, cli 2; stamps
16:02:21–16:02:52). RED of cases per variant: V1 25, V2 6, V3 10, V4 2, V5 1, V6 4, V7 5, V8 1, V9
4, V10 1, X1 3, X2 2, X3 3, X4 5, X5 2, X6 3, X7 1, X8 3, X9 9, X10 4, X16 1, SA 11, SB 3, SC 3, SD
2, SE 1, SF 7, SH 2, SI 4, SJ 2, SK 3, SL 1, SM 1, SN 1, SO 2, SP 2, SQ 1, SS 1, ST 2, **SR 0**
(disclosed). Full suites on FIX: yaml 1040/0, compiler 344/0; gates (javac `-Xlint:all
failOnWarning`, `pmd:check`, `spotless:check`) exit 0; docs-reference 5 classes green after regen
(611 codes); `sync-content` 69/99, `lint-prose` exit 0 and exit 1 on a planted 75-word control.
Logs: `s-5a/logs/`.

**Uncovered, disclosed.** SR (throw-before-warn) green on all 184 cells — cosmetic; the step-arm
"no csv default when `format:` is absent" is pinned by one predicate case, no lint/compile variant;
the `dev`/`host` one-line/exit-2 rendering of the compiler's and job-map fill's `TqlException` is
READ (one attacker RAN it on the guard-first classes: one line, `CLI EXIT=2`) — confirm on CI by
reading the annotations; the `RouteWatcher` "Watch: reload failed …" line is READ; the mounted-app
job arm is a no-op today (`AppSources` is ServiceLoader-only, no framework app declares a job) but
guarded; SL2 is designed here, not yet bracketed.

### PR 5b — a request-sourced value is refused before the SQL runs, and the fallback chain is one rule

**Change list (`44bc299fe`).** `tesseraql-compiler`: NEW `binding/FormatDeclaration.java` (record
`(key, declared, configDefault)`, `of(...)` blank → null); `binding/FormatSources.java` — `:23-36`
becomes `static Resolved resolve(Exchange, FormatDeclaration)` with `enum Provenance` and `record
Resolved(value, provenance, raw)` (the chain of decision 28, blank = unset), `:39-53` the pure
overload kept byte for byte (`LocaleResolution:65`), `:55-57` (5a's delegate) deleted — the binder
reads `ExportDeclarations.isSourceExpression` directly; NEW `binding/RequestFormats.java`
(package-private: `locale(exchange, declaration)`, `timezone(...)`, `judged(...)` with the non-text
refusal, the principal-locale fold, `fieldOf(source)`, `reject(...)` choosing `tql.input.<key>` vs
`tql.input.claim.<key>`); `binding/InputBinder.java:548-560` `reject` package-private + the
five-argument overload; `QueryExportBinder.java:19-20, :27, :33-34, :44-46`,
`FileExportStartProcessor.java:27-28, :40-41, :52-53, :74-78` (`formatted` computed BEFORE
`startExport`), `FileImportProcessor.java:39, :50, :58, :69, :79, :105` — `String` →
`FormatDeclaration`; `RouteCompiler.java:70` `fileDefaults`, `:127` `FileDefaults.of(manifest.config())`,
`:1427-1432`, `:1491`, `:1580-1581` → `formatting(key, declared)`; `formatDeclaration:1598-1602`
STAYS for the two `CatalogBinder` sites (`:1424`, `:1575`); `messages/en.yml:18-23`,
`ja.yml:16-21` the four keys (quoted `例:`). `tesseraql-yaml`: NEW `config/FileDefaults.java`.
`tesseraql-pipeline/.../SqlStep.java:46` `DOCUMENT_WRITE_FAILED` (LD 2802); `:280-290` the wrap;
`:346-349` the `DocumentWriteFailure` arm between the `TqlException` and `UncheckedIOException`
arms; `:549` the marker class and `documentError(...)`. `tesseraql-operations`:
`batch/StepContext.java:67-74` `Collaborators` + `fileDefaults()` after `appHome():156`;
`batch/JobExecutor.java:94` the field, the setter after `fileTransfers` (`:228`) and before
`filePush`'s javadoc (`:230`), `:539-541` into `Collaborators`; `batch/ExportStepRunner.java:45-48`
`.withFormatting(context.fileDefaults().localeOr(export.locale()), …timezoneOr(export.timezone()))`.
`tesseraql-runtime/.../TesseraqlRuntime.java:1157`, `:1185` `.fileDefaults(fileDefaults)`,
`:1703-1712` `PollSources` gains it; `PollSources.java:47-56`, `:112-113`. `tesseraql-cli/.../JobCommand.java:435`
`.fileDefaults(FileDefaults.of(manifest.config()))`. Tests and docs: §Docs, and the tables below.

**Guards.** Fixture for the ITs (`s-5b/gen.py`): `events(name, held_at timestamptz, fee numeric)`
one row at `2026-01-15 22:30:00+00`, `probe_seq` PRIMED (`setval(…, 1, true)`), config
`tesseraql.files.timezone: Asia/Kolkata` (a half-hour offset no host runs in) and `locale: de`,
`i18n.locales: [en, ja, de]`, bearer HS256; `@BeforeAll` asserts the JVM zone ≠ Kolkata and sets
`Etc/UTC`/`Locale.US`, restored in `@AfterAll`. Expected cells: SOURCE `Asia/Tokyo` → `07:30`; CONFIG
→ `04:00` next day; PLATFORM → `22:30`; a flipped LA host → `14:30`. Routes `x.seq`, `x.loc`,
`x.plain`, `x.literal`, `x.claim`, `x.reqloc`, `x.codec` (untyped `fee` with `format: 'dd.MM.yyyy'`
— passes 5a's untyped arm, refused by `DecimalFormat` at the cell), `x.badsql`, `x.feseq`, `x.impc`,
`x.impcfg`, poll job `intake`, job `nightly` (`report` declares nothing; `tokyo` `timezone:
Asia/Tokyo`) — every fixture lint- and boot-clean under 5a.

| class · method | assertion | red on / hazards |
|---|---|---|
| compiler · `RequestFormatsTest` U1 `aRouteLiteralIsNeverJudgedHere` | `Not/AZone` literal does not throw | V-belt |
| U2 `aResolvedSourceWinsOverTheConfiguredLiteral` | `query.tz=Asia/Tokyo`, config LA → Tokyo | V-cfgfirst |
| U3 / U4 / U5 | absent → LA; `"  "` → LA; absent + no config → null | HEAD; V-blank; V-cfgalways |
| U6 `theTwoKeysWalkTheChainIndependently` | locale SOURCE + zone CONFIG, values AND `provenance()` per key | V-cfgfirst (a value-only check is green on V-coupled) |
| U7 `aBadRequestSourcedZoneIsRefusedInTheInputBinderShape` | `fields[0]` EQUALS `{field: tz, code: timezone, message: tql.input.timezone, source: query.tz}` | HEAD, V-value, V-code, V-codecplaced |
| U8 `aBadRequestSourcedLocaleIsRefusedInTheInputBinderShape` | `ja_JP`, `japanese`, `xx-YY`, `und`, `x-private`, `no-NO-NY` refused; `ja-JP-u-nu-fullwide`, `nn-NO`, `no-NO-x-lvariant-NY`, `iw` pass | HEAD, V-lenient, V-fold-query — 29 |
| U9 / U10 | `body.report.loc` → `field == report.loc`; a claim → `field == source == principal.claim.zoneinfo`, `message == tql.input.claim.timezone`, log `IdP claim 'zoneinfo'` | V-lastseg; V-claim-generic-key |
| U11 `aNonTextClaimOrInputIsRefusedAsNotText` | `9` / list / map → "not a text value but a number / an array / an object" | V-stringify |
| U12 | no claim, config LA → LA | HEAD |
| U13 `theLogMessageBoundsAndStripsTheValue` | 300 × `A` + `\n` → message contains `...`, no `\n`, quoted value ≤ 43 (**adjudicated**, decision 3); NEL/LS stripped | V-unbounded, V-cc-ascii — 10 |
| U14 `theConfiguredLiteralIsNeverResolvedAsASource` | `of("timezone", null, "query.tz")` with `?tz=` → value `query.tz`, CONFIG, no throw | V-cfgresolve, V-belt |
| U15 `theNegotiatedLocaleIsNeverJudgedHere` | `LOCALE = und` → `und`, NEGOTIATED, no throw | V-reqloc-judged, V-belt, V-cfgfirst |
| U16 `anUnderscoreClaimLocaleIsReadInItsDashForm` | `en_US` → `en-US` …; `en_US.UTF-8` refused; query `ja_JP` STILL refused | V-nofold, V-fold-query, V-lenient |
| U17 / U18 | `" Asia/Tokyo"`, `asia/tokyo` refused, `UTC+9` passes; `PST`/`EST`/`JST` refused | V-trim-judge; V-short-ids |
| compiler · `BuiltinCatalogParityTest` C1 | the named list gains the four keys | HEAD, V-nokeys, V-enonly — 9 |
| compiler · `ErrorResponseRendererTest` C2 | `httpStatus(LD 2802) == 500` | V-400 |
| docs-reference · `StatusMappingLedgerTest.RECORDED` | contains `TQL-LD-2802` | without the entry: red (RUN) |
| runtime · `ExportRequestFormatsIntegrationTest` I2 `aBadRequestSourcedZoneIsRefusedBeforeTheSqlRuns` (runs FIRST) | `Tokyo`, `PST`, `%20Asia/Tokyo`, `Asia/Tokyo%20`, `asia/tokyo`, `Asia/Tokio` → 400, `TQL-FIELD-2001`, `fields[0].field == tz`, `.code == timezone`, `.source == query.tz`, no `value`, `.message` equals the en text, **`(last_value, is_called)` unchanged** | HEAD; V-codecplaced (only through the pair on the first request); V-short-ids, V-trim-judge (500 `TQL-LD-2802`); V-value; V-code; V-nokeys — 5, 6, 36 |
| I1 | `?tz=Asia/Tokyo` → 200 `07:30`, pair advanced | control; V-cfgfirst |
| I3 | `?loc=ja_JP` → 400 `code: locale`, pair unchanged; `ja-JP-u-nu-fullwide` → `１,２３４.５０`; no `loc` → `"1.234,50"` | HEAD; V-fold-query; V-qeloccfg — 29 |
| I4 / I5 | no `tz` → `04:00`; `?tz=` → `04:00`; under `TimeZone.setDefault(LA)` still `04:00` | HEAD (`22:30` / `14:30`) — 15, 3 |
| I6 / I6b | bad `zoneinfo` (`Asia/Tokio`, `9`, `["Asia/Tokyo"]`) → 400 `field == principal.claim.zoneinfo`, pair unchanged, the claim text; `locale: en_US` → 200 `"1,234.50"`, `de_DE` → `"1.234,50"`, `japanese` → 400 | HEAD; V-claim-generic-key; V-lastseg; V-nokeys; V-nofold; V-lenient |
| I7 `aFileExportRefusalLeavesNoRowAndTheDocumentsRenderTheChain` | `{"tz":"Tokyo"}`, `{"loc":"ja_JP"}`, `{"tz":9}` → 400; zero rows in `tql_file_transfer`/`tql_job_execution`; `{"tz":"Asia/Tokyo"}` → COMPLETED and the document has `07:30`; `{}` → `04:00` AND `"1.234,50"`; the fullwide locale applied | HEAD (202 → FAILED + rows); V-feafterstart; V-fenotapplied; V-fenocfg; V-fe-loc-dropped — 7 |
| I8 / I8b / I8c | file-import claim `japanese` → 400, `imported` unchanged, zero rows; `de-DE`/`de_DE`/`en_US` parse; no `locale:` → the configured `de` parses `1.234,50`; `request.locale` served not judged | HEAD; V-nofold; V-impnotapplied; V-lastseg; V-impnocfg; V-belt |
| I9 `aRefusedValueNeverReachesTheLogAtInfoOrAbove` | stderr window around `%0AFORGED`, 300 × `A`, `Tokyo` → no line at INFO+; **presence control**: `x.codec` logs exactly one `ERROR … Route 'x.codec' failed with TQL-LD-2802` | HEAD; V-warn-quiet; V-400 — 8 |
| I10 / I11 | `x.codec` → 500 `TQL-LD-2802`, message `Writing the csv document failed after the query ran`, names `x.codec.csv` not `export.sql`; `x.badsql` → `TQL-SQL-2500` naming the sql path | HEAD; V-400; V-allcodec |
| I12 `anExportStepWithNoDeclarationRendersInTheConfiguredZoneAndLocale` | `runJob("nightly") == COMPLETED`; `report` document `04:00` AND `"1.234,50"`; `tokyo` `07:30` | HEAD; V-cfgwins; V-jobnoloc — 19 |
| I15 `aPollImportWithoutALocaleReadsTheConfiguredLocale` | `omega,"2.345,60"` → `.done/`, `2345.60` | HEAD (`.error/`); V-poll-nocfg |
| runtime · `ExportFormatDefaultsEdgeIntegrationTest` I14 | `defaultLocale: ja_JP` (folds to `und`) app: `x.reqloc` → 200 `"1,234.50"` (HEAD's bytes) | V-reqloc-judged, V-belt, V-cfgfirst (500 on every request) |
| I16 | `temp.store: db`, `maxBytes: 100`, `x.cap` → 500 `TQL-LD-2802`, pair advanced, message has `Spool exceeds tesseraql.temp.maxBytes`, not `export.sql` | HEAD, V-io-sql, V-400 |
| I17 | `tesseraql.files.timezone: ${NOPE_ENV}` + a `query-json` route + a `sql` job: boots, 200, COMPLETED | V-eager-config (`TQL-YAML-1101` at boot) |
| cli · `JobCommandIntegrationTest` I13 `theCliJobRunReadsBothConfiguredKeys` | `job run nightly` exits 0, the `report` document `04:00` AND `"1.234,50"` | HEAD; V-onesite; V-jobnoloc |

**Variants (38).** V-belt, V-cfgfirst, V-blank, V-cfgalways, V-coupled, V-value, V-code,
V-lenient, V-lastseg, V-stringify, V-unbounded, V-cfgresolve, V-codecplaced, V-allcodec, V-400,
V-cfgwins, V-onesite, V-nokeys, V-enonly, V-fenotapplied, V-fenocfg, V-impnocfg, V-impnotapplied,
V-qeloccfg, V-jobnoloc, V-feafterstart, V-reqloc-judged, V-nofold, V-fold-query, V-trim-judge,
V-short-ids, V-warn-quiet, V-poll-nocfg, V-eager-config, V-io-sql, V-claim-generic-key, V-cc-ascii,
V-fe-loc-dropped.

**Bracket (RUN).** `base built 2026-09-12T15:43:21Z mode=strict rc=0` (the reactor's `-Xlint:all
… -Werror`); 38 variants built 16:18:40Z–16:20:01Z, each with `BUILD-STAMP`, `run-all.sh` refusing a
stamp-less variant (an earlier run after `make-variants.py` had recreated the directories ran 37
columns as HEAD — caught by the stamp count); **BRACKET 16:20:10Z–16:28:56Z**, 40 runs, every run
printing `SOURCE <class> <- <location>` for eleven classes (`ColumnValues` always the `.m2` core
jar) and `RESOURCE en.yml`; JVM `-Duser.timezone=Etc/UTC -Duser.language=en -Duser.country=US`; a
fresh database per run; I2 first. **FIX green on all 40 rows; HEAD red on 33** (green on C2, I1,
I8b, I8c, I11, I14, I17 — controls and kept behaviour); **every variant red on ≥ 1 row; none green
on every row.** Single-cell columns: V-codecplaced by I2's pair on the FIRST request; V-feafterstart
/ V-fenocfg / V-fenotapplied / V-fe-loc-dropped by I7's rows and documents; V-onesite by I13;
V-poll-nocfg by I15; V-eager-config by I17; V-io-sql by I16; V-warn-quiet by I9; V-reqloc-judged
by U15 and I14; V-nofold by U16/I6b/I8. Also RUN: compiler 314/314, pipeline 29/29, operations
108/108 with the fix classes first; `PollSourcesTest` recompiled 6/6 (HEAD's copy 1/6,
`NoSuchMethodError`); the parity test red on HEAD's catalogs; docs-reference on a tree copy 65/66
(the one is a copy artefact); the generator reproduces HEAD's two pages byte for byte and the fix's
regen changes `reference-config.md:133-134` and adds the 2802 row; both docs scripts exit 0;
`probe-cli-badkey.out`. Full matrix: `s-5b/out/MATRIX.txt`.

**Uncovered, disclosed.** A third `new JobExecutor(` site without `.fileDefaults(...)` keeps the
platform default silently; the judge after an `http:` fetch (decision 36, no guard counts partner
hits); `timezone: principal.subject` → 400 blaming the caller (filed); a declared input's `default:`
judged as the caller's (filed); "judge when EITHER key is SOURCE" coupling green while 5a keeps
literals valid (only V-coupled built); the DEBUG line's content pinned at the unit level only; the
two `CatalogBinder` sites unchanged; a file-import declaring `request.locale` under an `und`
default (I14 covers the query-export twin only).

### PR 5c — a valid `splitBy:` takes effect on every surface

**Change list (`44bc299fe`).** `tesseraql-core/.../files/SplitExport.java:39-43` `BUNDLE_FORMAT`,
`BUNDLE_CONTENT_TYPE`, `public static String zipName(String)` (moved verbatim);
`FileWriteSpec.java:60` `public boolean splits()`; `ExportWrite.java:42` → `spec.splits()`;
`FileTransferService.java:268` `InlineResult` javadoc ("the name it is recorded under — a split
export's is the bundle's"). `tesseraql-pipeline/.../SqlStep.java:362-372` reads the core constants;
`:434-446` deleted (moved, not copied — after 5b these anchors sit ~20 lines lower).
`tesseraql-operations/.../batch/StepContext.java:420-424` javadoc, `:433` the `{key}` pass-through
before `resolve`; `files/JdbcFileTransferService.java:275-287` `startExport` records
`split ? BUNDLE_FORMAT : request.format()` and `split ? zipName(filename) : filename` (the writer
keeps the pattern); `:291-304` `exportInline` likewise and `:350` `new InlineResult(transferId,
recorded, rows)`; `:463-468` `download()` types by the recorded format, the codec looked up only on
the other arm. `ExportStepRunner` NOT edited. Tests: MOVE `SqlStepZipNameTest` →
`tesseraql-core/.../SplitExportZipNameTest`; `FileWriteSpecTest` +1; NEW
`StepContextInterpolateTest`; `BatchJobIntegrationTest` +1 (fixture before `:996`, test + helpers
after `:254`); `FileTransferIntegrationTest` +3 tests, 3 route writers after `:464`.

**Guards.**

| guard | fixture | assertion | red on | hazards |
|---|---|---|---|---|
| G1 runtime · `BatchJobIntegrationTest.aSplitExportStepBundlesOneDocumentPerGroup` | job `batch/split/` over a `VALUES` list of two groups, `filename: orders-{batch.businessDate}-{key}.csv`, plus a `push:` step `as: "{steps.extract.filename}"` into `outbox/split` | `COMPLETED`; the transfer's bytes are a ZIP with entries `orders-2026-03-31-a.csv`, `orders-2026-03-31-b.csv`; `recent()` row `format == zip`, `filename == orders-2026-03-31.zip`; the pushed file exists under the bundle name with the same entries | HEAD (`:262` FAILED 2858), v-a, v-b, v-c, v-d (2857), v-e, v-g (`…-{key}.csv` in the outbox), v-h (`csv` on the row), v-i, v-j, v-m | 18, 19 |
| G2 runtime · `FileTransferIntegrationTest.aSplitExportDeliversTheBundleItIs` | file-export `orders-{key}.csv`, `splitBy` over two groups | 202 → COMPLETED; status JSON `filename == orders.zip`; `/file` `Content-Type: application/zip`, `Content-Disposition … filename="orders.zip"`, body begins `PK`; `recent()` `zip` / `orders.zip` | HEAD (`:157` `orders-{key}.csv`), v-e, v-h, v-j, v-m | 20 |
| G3 `anExportNamedZipKeepsItsCodecsType` | csv route `filename: notes.zip` whose FIRST column is labelled `PK` (the body begins with the bytes `PK`) | `text/csv`, `filename="notes.zip"`, `recent()` `csv` | v-e (`.zip` suffix rule), v-n (bytes sniffed) — green on HEAD (a control) | — |
| G6 `aBlankSplitByExportsOnePlainFile` | `splitBy: ""` csv route | COMPLETED, `text/csv`, `orders-blank.csv` | v-k (FAILED 2858), v-k2 (`application/zip`) — green on HEAD | — |
| G4 core · `SplitExportZipNameTest` (moved) | the nine rows | unchanged | v-i, v-j | — |
| G5 operations · `StepContextInterpolateTest` | `{key}` alone; `{batch.businessDate}-{key}`; `{typo}` | `{key}` passes through; the date renders; `{typo}` renders empty | v-a, v-c, v-f | pins the seam — a sentinel-swap implementation would be red here while G1 is green (disclosed) |
| G7 core · `FileWriteSpecTest.aBlankSplitByIsNotASplit` | `null`, `""`, `"  "`, `"x"` | `splits()` false/false/false/true | v-k (`:52`) | — |

**Variants (17).** v-a … v-n (the design's seven + the guard-sensitivity attack's seven), v-m
(writer handed the bundle name), v-n (type sniffed off the bytes), v-q (resolve-first — equivalent,
disclosed).

**Bracket (RUN).** Classpath-shadow pass `s-5c/shadow/logs/bracket-final.txt`, 18 columns × 39
assertions, 15:06:22–15:08:18 UTC (stamps in `shadow/logs/stamps.txt`: variant classes
15:05:28–15:06:02, `Guard.class` 15:06:07 compiled once before the pass, `.m2` core/operations jars
11:18:47/54, worktree clean before and after): HEAD 18 red (Z1 FAILED 2858), **fix 0**, v-a 10, v-b 4,
v-c 8, v-d 8 (Z1 COMPLETED — hazard 18), v-e 3, v-f 1, v-g 2, v-h 2, v-i 6, v-j 4, v-k 5, v-k2 2,
v-l abort at J3 (`TQL-LD-2801` for `zip`), v-m 12, v-n 1, **v-q 0** (equivalent). Real-harness
bracket (the shipped JUnit code under surefire, `-o -am`, surefire XML read; stamp =
`JdbcFileTransferService.class` mtime): fix 15:13:25 50/50 incl. the four new ITs; head 15:14:37 G1
RED `:262`, G2 RED `:157`, G3/G6 green controls; v-g 15:15:32 G1 `:293`; v-h 15:16:24 G1 `:275` and
G2 `:163`; v-k2 15:17:24 G6 `:198`; v-n 15:18:14 G3 `:186`; v-e 15:18:59 G1/G2/G3; v-k 15:19:40 G6
`:197` and `FileWriteSpecTest` `:52`. Static gates on the fix tree (`verify-units.log`
15:11:00–15:12:33): `./mvnw -o -pl tesseraql-core,tesseraql-pipeline,tesseraql-operations -am
verify` after one `spotless:apply` → BUILD SUCCESS (spotless, PMD, doclint, 3,384 unit tests); the
`InlineResult` javadoc under core's `-Xlint:all` + `failOnWarning`: BUILD SUCCESS. Docs (`docs.sh`
15:21:13–15:21:47): sync-content 69/98 exit 0, lint-prose 65 pages exit 0, `DocumentedCommandLineTest`
2 + `DocServiceTest` 25 green, all 31 docs-reference guard classes green. Container `s5c-pg`
removed.

**Uncovered, disclosed.** v-q (resolve-first) equivalent today; a duplicated `zipName` (only `git
grep` catches it); G5 over-specifies the seam; the mail part's `Content-Type` (`MailNotifier:233-238`
— the name IS pinned by `NotificationDeliveryTest:226`; a one-line content-type pin is optional and
outside 5c's files); the ops console's rendered HTML cell (`transfers.html:37,42` renders `recent()`
1:1, READ); the FAILED-split row cosmetic; pre-5c rows (served exactly as before — the `zip` arm is
not taken); the 202 body carries no name.

---

## Docs and CHANGELOG — the exact sentences, per pull request

Every published sentence below is under the 60-word limit and carries no "slice"/"PR" wording
(RUN through `lint-prose.mjs` and `sync-content.mjs` on a tree copy — `adjudication/docs-guards.log`).
Line numbers are on `44bc299fe`; a later PR re-anchors on the merged text.

### PR 5-0

`CHANGELOG.md`, `## Unreleased` → `### Fixed`, two entries after the existing redirect entry:

- **An Excel grid or placement export with a NULL cell no longer answers 500.** The workbook
  writers asked the temporal normalizer about every value before their own null arm, and it had
  no null arm of its own, so one NULL — text, a typed number or a typed datetime alike — failed
  the whole export (`TQL-SQL-2500` on a route, a FAILED transfer on a file-export, `TQL-LD-2810`
  on a job step) since the first release. A NULL is a blank cell, as it always was in a jxls
  report.
- **An export of a `time` column no longer answers 500 on an in-range value.** pgjdbc, H2, MySQL,
  MariaDB and SQL Server hand a `TIME` column over as `java.sql.Time`, whose `toInstant()`
  throws, so every `csv`, `excel` grid or placement and `pdf` export selecting one failed,
  declared or not, and no column `format:` could rescue it. A time of day now renders as
  wall-clock text — `22:30:00`, or the column's `format:` over the time in the export's locale —
  on `csv` and `pdf`, and as a real time cell in a workbook: the fraction of a day under
  `hh:mm:ss` or the declared cell format in a grid, under the declared cell format else the
  template's prototype style in placement. `timezone:` never shifts it, since a time has no date
  to shift, and a `type: date` or `type: datetime` on it invents none. DuckDB's `LocalTime`
  renders the same way, so a DuckDB `time` cell that read `22:30` now reads `22:30:00`; an H2 or
  DuckDB `time with time zone` (`OffsetTime`) renders with its offset dropped on `csv` and the
  workbook grid, and is still refused by the row spool on `pdf`, placement and split exports
  (`TQL-LD-2853`). A PostgreSQL `time with time zone` is not an `OffsetTime`: pgjdbc hands it
  over already moved into the server JVM's zone, and `timezone:` does not correct that. A MySQL
  `TIME` outside `00:00:00-23:59:59` is refused by its driver before the codec sees it, and
  MariaDB wraps one modulo a day.

`docs/file-transfers.md:121` — one bullet inserted after the `type:` bullet (before the
`locale:`/`timezone:` bullet), three sentences:

- A time-of-day column renders as wall-clock text (`22:30:00`, or `format:` over the time) on
  `csv` and `pdf`, and as a real time cell in a workbook grid or placement. `timezone:` does
  not shift it: a time has no date to shift. A PostgreSQL `time with time zone` reaches the
  codec already moved into the server JVM's zone by the driver, and `timezone:` does not
  correct that.

`docs/export-declarations.md` — this record. `ErrorIndex.java:619` — `"export-declarations.md"`
appended to `INTERNAL_DOCS`; `docs-site/nav.mjs:325` — `'export-declarations.md'` appended to
`EXCLUDED` with a two-line comment ("An export declaration is refused, or it takes effect: the codec
value arms, the literal judged at lint and boot, the request-sourced refusal, the split bundle —
designed 2026-09-12"). `InternalDocsSyncTest:49` — `assertThat(ErrorIndex.isInternalDoc("export-declarations.md")).as("the export-declarations record is registered").isTrue();`.

The audit record — decision 43's fact amendments (§The audit record below).

### PR 5a

`CHANGELOG.md`, `## Unreleased`:

**Added** — "**A mistyped export declaration is refused where it was written.** A `timezone:` the
JDK does not know, a `locale:` it cannot format (`ja_JP`, `japanese`), a `csv`/`pdf`
`columns[].format:` its parser refuses, an import `columns[].type:` the parser does not know, a
`startCell:` or `column:` that is not a reference or lies outside a workbook, a mixed-case `format:`
(`Excel`) and a request source naming nothing the surface binds are now a lint error and a boot
refusal with one code, `TQL-YAML-1063`, on a route's `export:`, a job step's `export:`, a route's
and a poll job's `import:` block and the `tesseraql.files.locale` / `tesseraql.files.timezone` keys
— judged by one predicate, only where the format reads the key: a workbook's `columns[].format:` is
its cell format and is never parsed, a jxls report's `timezone:` reaches no cell, a `csv` never
reads a template. `tesseraql job run` judges the same predicate — every job and the two
configuration keys — before it records an execution, and a hot reload judges the two configuration
keys once, refusing the reload as a whole instead of stubbing every route. Every message names the
app, the route or job step, the key and the bounded value. Before this, every altitude was silent:
a bad zone answered 500 after the extraction SQL had run, a `file-export` failed with no reason on
the wire, a job step failed every firing, and `import.locale: de_DE` parsed `1234,50` as `123450.00`
and completed. A request source on a job step or a poll job, or one naming a request input on a
`file-import` (which binds none), is refused for the first time; a configuration key that is a
source expression is refused too. A key the format never reads — `bom:`, `sheet:`, `startCell:` or
`template:` on the wrong format, `locale:` on a workbook, a `type:` the export does not render —
stays a lint error (`TQL-YAML-1005`) and is now a boot warning; a `csv`/`pdf` export declaring a
locale or zone over a column list with no typed or formatted column, and a jxls report declaring
keys it never reads, draw a lint warning. `TQL-FIELD-4622` no longer demands a `locale:` on an
`excel` export."

**Fixed** — "**The raw boot failures of an export declaration are shaped.** `startCell: 5B` used to
escape as an `IllegalStateException` naming neither the route nor the key; a `file-export` route
without an `export:` block, or with an `after:` lacking its statement, as a `NullPointerException`;
a template name the file system refuses as an `InvalidPathException`; `startCell: ZZZZ1` on a
workbook compiled and failed the first request inside the workbook library. Each is now the refusal
above (`TQL-YAML-1063`, `TQL-YAML-1041`, `TQL-YAML-1006`) naming the route and the key. A
`file-export` route with no `format:` now defaults to `csv`, as a `query-export` route always did,
instead of reaching the first request as the format `null`. A missing workbook or print template on
a route or a job step is refused at boot as it was at lint (`TQL-YAML-1006`)."

`docs/file-transfers.md:88` (the canonical block): `locale: de-DE               # csv/pdf only (a
workbook never reads it); or a request source`.

`docs/file-transfers.md:119-125` → the `type:` bullet and the `locale:`/`timezone:` bullet replaced
by three bullets, with 5-0's time bullet kept between the first and the second; the `bom:` bullet
(`:126-132`) untouched:

- `type:` (`date` / `datetime` / `number`) with `format:` renders a typed or formatted column
  through a date or decimal pattern on `csv` and `pdf`. On a workbook the string is the cell's
  own number format, in Excel's vocabulary (`d-mmm-yy`, `0.00E+00`), and no Java parser ever
  sees it. A column with neither is written as the driver's `toString()` of the value on `csv`
  and `pdf`; the Excel grid and placement modes type every temporal cell. A jxls report
  (`template:` without `startCell:`) hands the template the raw values and reads none of these
  keys.
- (5-0's time-of-day bullet, unchanged)
- `locale:` and `timezone:` drive those patterns, and reach only a typed or formatted column.
  The linter warns when a `csv` or `pdf` export declares them over a column list with none; it
  cannot see a column the query derives. Each key stands on its own. A key is a literal such
  as `ja-JP` or `Asia/Tokyo`, or on a route a request source: `principal.claim.locale`,
  `query.tz` naming a declared `input:`, `body.tz` (a declared input, unless
  `inputPolicy.unknownFields: ignore` admits any field), or `request.locale` (the negotiated
  request locale). **[5a writes:]** A route that declares neither key falls back to the app
  configuration keys `tesseraql.files.locale` and `tesseraql.files.timezone`, which are
  literals, never source expressions. **[5b replaces that one sentence with the chain, below.]**
  `locale:` drives nothing in a workbook — a cell carries a value and a cell format, and the
  reader's own locale renders them — so the linter refuses it on `format: excel`.
- Every literal is judged where it is written, and only where the format reads it. A zone the
  JDK does not know, a language tag it cannot format, a `csv`/`pdf` pattern its parser refuses,
  a malformed or out-of-workbook cell reference, or a mixed-case `format:` is a lint error and
  a boot refusal with the same code (`TQL-YAML-1063`). The message names the app, the route or
  job step, the key and the value. A key the format never reads — `bom:`, `sheet:`,
  `startCell:` or `template:` on the wrong format, `locale:` on a workbook, a `type:` the export
  does not render — is a lint error (`TQL-YAML-1005`) and a boot warning: the runtime serves
  without it. `tesseraql lint`, the boot and `tesseraql job run` share the one check.

`docs/file-transfers.md:138-140`: "A template path that does not exist is refused by the linter and
at boot (`TQL-YAML-1006`) on the formats that read one — a workbook, a print template, a module
format — instead of failing the first request; on `csv` the key is inert and warned about.
`startCell:` without a template is refused: the mode a declaration selects should be the mode it
names."

`docs/file-transfers.md:416` (the error-code table) — four rows inserted above `TQL-ROUTE-3101`:
`TQL-YAML-1063` "An `export:` or `import:` literal the runtime cannot honour where it reads it — a
zone, a language tag, a `csv`/`pdf` column pattern, an import column type, a cell reference, a
mixed-case format name, or a request source naming nothing the surface binds; a lint error and a
boot refusal"; `TQL-YAML-1005` "An option that cannot apply where declared (`bom:`, `sheet:`,
`startCell:`, `template:` on the wrong format, `locale:` on a workbook, a `type:` the export does not
render): a lint error, a boot warning. As a warning: a declaration honoured less than it reads";
`TQL-YAML-1041` "A missing piece: the `export:` block on a `file-export`, an `after:` without its
statement, `splitBy:` without `{key}`"; `TQL-YAML-1006` "A template that is not there, or the wrong
kind of file for the format, at lint and at boot". The `TQL-LD-2810` row is NOT touched (5b's).

`docs/jobs.md:483-484`: "`template:` resolves beside the job file; `locale:` and `timezone:` are
literals (a job has no request to resolve them from), and a request source on a step is refused by
`tesseraql lint`, at boot and by `tesseraql job run` (`TQL-YAML-1063`), as is a zone or a language
tag the JDK cannot honour." `:487`: "(`TQL-YAML-1005` at build time)". `:766` the 1005 row gains "a
workbook option on a pdf or a csv, `template:` on a csv, or `locale:` on a workbook. At boot the
runtime warns and serves without the key"; a new row above it: "An `export:` or `import:` literal
the runtime cannot honour: a zone, a language tag, a `csv`/`pdf` column pattern, an import column
type, a cell reference, a mixed-case format name, or a request source on a step or a poll job.
Refused at boot and by `tesseraql job run` with the same code | `TQL-YAML-1063`".

`docs/printable-documents.md:120`: "No runtime module depends on the PDF engine — `tesseraql-pdf`
is opt-in; without the jar a `format: pdf` query-export route fails at boot (`TQL-LD-2801`), while
a `file-export` route or a job step fails with the same code at its first request or run."

`docs/export-pipeline.md:394` (site-excluded): "a `splitBy` without it is a lint **error**
(`TQL-YAML-1041`)".

`docs/code-catalogs.md:122-126`, `docs/lookups.md:336`, `:355-358`, `:766-768`: each "an export
must declare `locale:`" sentence becomes "a `csv` or `pdf` export must declare `locale:` … A
workbook never reads `locale:` — its cells carry values the reader's own locale renders — so an
`excel` export is not asked for one, and is refused when it declares one
([file-transfers.md](file-transfers.md))"; lookups decision 12's Export row: "the export's declared
`locale:` on `csv` and `pdf` (a literal, or a request source the route binds); a workbook reads
none".

The shared schema (`tesseraql-yaml/src/main/resources/schema/tesseraql-defs-v1.schema.json:118-125`
and the byte-identical `examples/scaffold-demo-app/.vscode/` copy) — edited once, final:

- `locale`: "The locale date and number patterns render in, reaching typed or formatted columns
  on csv and pdf (a workbook never reads it and is refused for declaring it). A literal BCP 47 tag
  the JDK can format (`ja-JP`), or on a route a request source such as `principal.claim.locale`,
  `request.locale` or `query.lang` naming a declared input; a bad literal is refused at lint and
  boot (TQL-YAML-1063). Unset, `tesseraql.files.locale` applies. A job has no request, so a step's
  is a literal."
- `timezone`: "The zone date and time values render in, reaching typed or formatted columns on
  csv and pdf and every temporal cell of an Excel grid or placement (a jxls report ignores it). A
  literal `ZoneId` (`Asia/Tokyo`, `+09:00`; region ids are case-sensitive), or on a route a
  request source such as `query.tz` naming a declared input; a bad literal is refused at lint and
  boot where the format reads it (TQL-YAML-1063). Unset, `tesseraql.files.timezone` applies. A
  job has no request, so a step's is a literal."

The import `locale` description (`:60-62`) is untouched (true after 5b, and 5b's prose carries the
file-import source restriction). Regenerated: `docs/reference-error-codes.md` (the 1063 row;
1005/1041/1006 re-described), `docs/reference-yaml-surface.md` (the two descriptions),
`docs/reference-config.md:133` (`FilesConfigRules` reads the keys).

### PR 5b

`CHANGELOG.md`, `## Unreleased`:

**Changed** — "**`JobExecutor` gains `fileDefaults(FileDefaults)`, the app-wide
`tesseraql.files.locale` / `tesseraql.files.timezone` an export step falls back to.** Both
executors — the served runtime's and `tesseraql job run`'s — wire it; an embedder that builds its
own passes `FileDefaults.of(config)` or keeps the platform's."

**Fixed** — four entries: (1) "**A bad request-sourced export zone or locale is refused before any
SQL runs.** A `query.`/`params.`/`body.` source on `export.locale:`/`export.timezone:` that resolves
to a value the server cannot use (`?tz=Tokyo`, `?loc=ja_JP`) answers 400 `TQL-FIELD-2001` with a
field error naming the input, code `timezone` or `locale`, and the catalog's text in the caller's
language. Nothing runs: no extraction, no transfer row, no execution row, no log line at INFO or
above (the DEBUG line carries the value, bounded and control-stripped). It used to be a 500
`TQL-SQL-2500` after the first batch was fetched on a synchronous export, a 202 then `FAILED` with
no reason on a file-export, and a `Locale.ROOT` document at 200 for a mistyped locale. A sign-in
claim (`principal.claim.zoneinfo`, `principal.claim.locale`) is held to the same rule on exports
and file-imports alike and named by its expression under its own message text; a claim that is a
JSON number, array or object is refused as not text rather than stringified; a locale claim spelled
`en_US`, as OpenID Connect allows, is read as `en-US`. On a file-import a `principal.claim.locale`
of `japanese` used to parse `99,90` as `9990.00` under `Locale.ROOT`; a `query.`/`body.` source on
`import.locale:` never resolved there and still does not (a file-import binds no request inputs).
The negotiated `request.locale` is the framework's own value and is never judged." (2) "**An
unresolved request source falls back to the app configuration.** A route whose
`locale:`/`timezone:` names a source that resolves to nothing (no `?tz=`, a sign-in without the
claim, a blank value) now uses `tesseraql.files.locale`/`tesseraql.files.timezone` before the
platform default, as the reference always said; it used to skip the configuration. The two keys
fall back independently, on `query-export`, `file-export` and `file-import` alike." (3) "**A job
reads `tesseraql.files.locale` and `tesseraql.files.timezone`.** An export step renders in the
configured locale and zone for each key it leaves unset, through both the served runtime and
`tesseraql job run`; a poll-triggered import parses in the configured locale when `import.locale:`
is unset. Both used to use the JVM's." (4) "**A failure while writing an export document is filed
under its own code.** `TQL-LD-2802` "Writing the csv document failed after the query ran: …" names
the format and the file — a codec, a column format, or the spool under it
(`tesseraql.temp.maxBytes`); it used to be `TQL-SQL-2500` "SQL execution failed" naming the
extraction's SQL file, which had run to completion. A failure of the statement itself is still
`TQL-SQL-2500`."

`docs/file-transfers.md` — in the `locale:`/`timezone:` bullet 5a wrote, the one chain sentence
("A route that declares neither key falls back …") is replaced by: "Each key falls back on its own:
the route's literal, else the request source when it resolves to a value, else the app
configuration keys `tesseraql.files.locale` and `tesseraql.files.timezone` (literals, never source
expressions), else the platform default. A job step's declaration is a literal and falls back to
the same keys. A request-sourced value the server cannot use — `?tz=Tokyo`, `?loc=ja_JP` — is
refused before the extraction runs, as a `TQL-FIELD-2001` field error naming the input with code
`timezone` or `locale`; a file-export refused this way starts no transfer. A sign-in claim is held
to the same rule and named by its expression (`principal.claim.zoneinfo`); a locale claim spelled
`en_US` is read as `en-US`. An offset in a URL must be percent-encoded (`?tz=%2B09:00`), because
`+` is a space in a query string."

`docs/file-transfers.md:386-387` (the import `locale:` bullet): "`locale:` — drives
`type:`/`format:` parsing of dates and numbers: a literal, `principal.claim.locale`, or
`request.locale`, with the same configuration fallback as exports. A file-import route binds no
request inputs, so a `query.`/`body.` source cannot resolve here."

`docs/file-transfers.md` error-code table — two rows added and one rewritten: `TQL-FIELD-2001`
(`code: timezone` / `locale`) "A request-sourced zone or locale the server cannot use, refused
before any SQL runs — 400"; `TQL-LD-2802` "The document could not be written after the extraction
ran — a codec, a column format or the spool; the message names the format and the file — 500";
`TQL-LD-2810` "The file-transfer service failed — creating its schema, recording a transfer, or
running an export step; the message carries the cause".

`docs/jobs.md:483-484` — appended to 5a's sentence: "For each key a step leaves unset the app
configuration (`tesseraql.files.locale`, `tesseraql.files.timezone`) applies, as it does to a
route."

`docs/csv-import.md:307-309`: "The read spec is resolved per request — `locale:
principal.claim.locale` or the negotiated `request.locale`; a file-import binds no request inputs,
so `query.*` is not a source here — and the commit is a *different* request, where that expression
may resolve differently …". `docs/declarative-validation.md:45-46` — the code list gains
`timezone`, `locale`.

Regenerated: `docs/reference-config.md:133-134` (`tesseraql.files.locale` readers
`RouteCompiler.java, FileDefaults.java, CatalogLocaleRules.java, FilesConfigRules.java`, pages +
`[jobs]`; `tesseraql.files.timezone` reader `FileDefaults.java, FilesConfigRules.java`);
`docs/reference-error-codes.md` (611 → 612 codes after 5a's 1063: the 2802 row; `TQL-FIELD-2001`
gains `[file-transfers]`). The schema is NOT touched (decision 41).

### PR 5c

`CHANGELOG.md`, `## Unreleased` → `### Fixed`, one entry: "**A `splitBy:` export delivers as the
ZIP it is, on a job step and on `file-export`.** A job step declaring `splitBy:` could never run:
the step's filename interpolation rendered `{key}` empty before the split writer demanded it, so
every run failed with `TQL-LD-2858` blaming the author for the placeholder they wrote. `{key}` now
passes through interpolation untouched and is replaced once per group, beside
`{batch.businessDate}`. A `file-export` with `splitBy:` completed, but its transfer was recorded
under the per-document pattern and the codec's content type — the status JSON said
`orders-{key}.xlsx`, the download was served as a workbook, and Excel refused the ZIP it was
handed. A split transfer is now recorded as the bundle: `filename` is the stem plus `.zip`,
`format` is `zip`, the operations console lists it so, and the download, the console's file link,
a `push:` step and a mail attachment all serve it as `application/zip` under that name — the same
name and type the synchronous `query-export` already answered. A step's `steps.<id>.filename`
carries the bundle's name. The bundle name derivation moved from the query-export step into core
beside the split writer."

`docs/file-transfers.md:99`: `splitBy: customer_id        # one document per value, delivered as one
ZIP named for the stem (see below)`. `:225-227`: "One file still leaves the export, so downloads,
push destinations and mail attachments are unchanged. That file is the bundle: `invoice-{key}.pdf`
downloads as `invoice.zip` with `Content-Type: application/zip`, on `query-export` and `file-export`
alike. A `file-export` transfer records the bundle — its status reports `invoice.zip` as the
`filename`, and the operations console lists the transfer as `zip`. The type follows the recorded
format, never the name: a csv an author called `notes.zip` is still served as `text/csv`. A blank
`splitBy:` is no split. One group still produces a ZIP and no rows produce an empty one — the output
shape is a property of the route, not of today's data." `:261-262`: "— the transfer state: `status`
(`RUNNING`, then `COMPLETED` or `FAILED`), `rowCount`, `filename` (for a `splitBy:` export, the
bundle's name), `downloaded`, and `fileUrl` once completed".

`docs/jobs.md:482-484` (the `filename:` bullet): "**`filename:` interpolates `{dotted.path}` context
values** — `{batch.businessDate}` being the one that matters. `{key}` is not a context value: on a
`splitBy:` step it passes through to the bundle, where each group's document replaces it, and the
transfer is recorded under the bundle's name (`orders-{batch.businessDate}-{key}.csv` →
`orders-2026-03-31.zip`), which is what `steps.<id>.filename` then carries." (5a's and 5b's
sentences about `locale:`/`timezone:` stay in the bullet.)

`docs/download-name-and-bytes.md:179` (decision 6): append "Moved to core as `SplitExport.zipName`
with its test (`SplitExportZipNameTest`) by the export-declarations record's 5c, which found that
the file-export and job surfaces never called it."

### The audit record (`docs/audit-medium-leads.md`, `docs/download-name-and-bytes.md`)

**5-0 carries the facts** (MEASUREMENT §2.6), each paragraph amended once:

- `:30` (the F126 row): the lead text corrected — the code is `TQL-SQL-2500` (never
  `TQL-ROUTE-5000`), raised by `SqlStep.export`'s catch-all, which files the codec failure as a SQL
  failure naming the SQL file, after the extraction ran; "Locale is safe by contrast" is false (ROOT
  at 200; ×10/×100 on the import arms); the configuration key is not static (a per-request source
  resolves through it); a `zoneinfo` claim reaches `ZoneId.of` as string, number, array or nested
  object; a request header of the input's name is an input; the stack trace exists only where a
  logging backend does. Last column: "measured record `export-declarations.md`; PR 5-0 SHIPPED #<n>
  — the two codec crashes the measurement found inside `ColumnValues.toZoned` (an Excel
  grid/placement NULL cell → 500; a `java.sql.Time` column → 500 on five of six drivers); 5a / 5b /
  5c pending".
- `:120-121`: "**F126's quiet halves outnumber the loud one**" — the three were mis-drawn: N3 is
  dead on its premise, N2 is not the half described, and the harmful defects on this path were
  unfiled (the Excel NULL cell and `java.sql.Time`, loud; the zoneless-timestamp shift and the
  `import.locale` corruption, silent).
- `:152-155` (N2): "for any column without `type:`" → "without `type:` or `format:`", csv/pdf only
  (Excel grid/placement zone every temporal, a jxls report zones nothing); the quoted numbers
  describe a `timestamptz`; on a zoneless `timestamp` the typed cell moves with the host; only
  procurement selects a temporal, and its harm is the driver's `.0` text; N2's fix is the
  temporal-semantics design's.
- `:156-162` (N3): dead — `codes` never reaches any export surface (three RUNs with an HTML
  control); 4622 fires on the shipped `users.export`/`users.print` the moment a multilingual catalog
  exists; the live batch-path gaps are different and are the record's; slice 5 and 4c do NOT share
  `CsvFileCodec.write` — the edit sites are named in the record.
- `:181` (#9): `TransferStatus` HAS an `errors` field the export path never fills; the reason IS
  recorded (`exit_message`) and served by the ops API; the real gaps are the wire projection, the
  card text, the console row, the 2,000-character cliff in `bindFinish`, `null` for message-less
  exceptions, no TQL code; a refusal at the start POST gives the async recipe a 400 before any row
  exists (5b).
- `:213` (#23): "(boot refuses it)" is query-export only — file-export boots and 500s
  `TQL-LD-2801` at the first POST, a job step fails at the first run; the case-fold half is 5a's,
  the unknown-name half is F82 slice 2's (the boot check is the TCCL defect).
- `:277` (the slice-5 row): "Subsumes the two unfiled halves" → N3 dead, N2 deferred with the
  zoneless-shift decision; no shared `CsvFileCodec.write`; the `bom:` lint/boot gap closes by a
  boot WARN, not a refusal; size M → four PRs plus a record; the row gains what it was missing
  (the Excel NULL 500, the `java.sql.Time` 500, `import.locale` corruption, job-step and file-export
  `splitBy:`, the unshaped boot refusals, the config-level source, the job path's missing config
  fallback, the request-time 4xx, the fallback decision). Status: "PR 5-0 SHIPPED #<n>; 5a / 5b / 5c
  pending".
- "Defects surfaced that the audit does not carry" — a new item after item 2: "**Any Excel grid or
  placement export with one NULL cell answered 500, and any csv/excel/pdf export of a `time` column
  answered 500 on five of six drivers** (high / medium-high, loud; both since v0.1.0; both inside
  `ColumnValues.toZoned` — no `case null`, and `java.sql.Time.toInstant()` throws). Fixed in PR 5-0
  (`export-declarations.md`)."
- `docs/download-name-and-bytes.md` decision 15 (`:300-316`), appended: "The export-declarations
  record answered the charter with WARN-and-continue at boot from the same predicate the linter
  runs, and it judges a value only where the format reads it: the measured cost of refusing an
  inert key is a whole-stack outage naming nothing. `format: Excel` fails at boot as an unknown
  format on query-export only; a `file-export` or a job step carries it to the first request or
  run." Decision 6 (`:176-181`): "Query-export only: a file-export `splitBy:` transfer is served
  under the literal `r-{key}.<ext>` with the codec's content type over ZIP bytes (5c)." The
  export-hygiene list (`:880-888`) gains: the drain-spool leak (fix at `SpooledRows.drain`/
  `ExportWrite.write`), the async reason projection, the Excel `format:` string written verbatim as
  the cell format, the PDF template's `Locale.ROOT` context, the `TQL-LD-2856` missing-template
  code, the `'null'` format text, the caller's principal in `params_json`, `TQL-LD-2801`'s hint that
  always names the excel module, `TQL-FIELD-4622` guarding nothing. `:889` (the slice-5 line): "**The
  export-declarations record**: the lint/boot gap for inert export keys closes with a boot warning
  from the linter's own predicate, never a refusal; the same un-linted app also shipped a split
  bundle mislabelled under the per-document name on a VALID declaration."

**5a, 5b, 5c each append their status** to `:30` and `:277` ("PR 5a SHIPPED #<n>: …" one clause
each); 5c also appends the note to `download-name-and-bytes.md:179`.

---

## The verify sets and the manual guards

The named set is the iteration set; the standing pre-push ritual is the full clean verify of the
whole reactor redirected to a file (`./mvnw -B -ntp clean verify > verify.log 2>&1; echo $?` — a
pipe masks the exit code), then both docs guards by hand (`cd docs-site && node
scripts/sync-content.mjs && node scripts/lint-prose.mjs` — neither runs in `mvn verify`), then
"Maven verify on Java 25" confirmed on the PR head with its annotations read. `-am` builds
dependencies, not dependents — `tesseraql-docs-reference` is always named; a `docs/` edit names
`tesseraql-cli` (`DocumentedCommandLineTest`) and `tesseraql-studio` (`DocServiceTest`). Read
`FileTransferIntegrationTest`'s surefire XML, not its `.txt` (`Tests run: 0` is a `@Nested`
artefact).

- **5-0**: `-pl tesseraql-core,tesseraql-operations,tesseraql-excel,tesseraql-pdf,tesseraql-runtime,tesseraql-docs-reference,tesseraql-cli,tesseraql-studio -am`.
  No schema edit, no regen (`GeneratedReferenceTest` proves the references byte-identical).
- **5a**: `./mvnw -q -pl tesseraql-core,tesseraql-yaml -am install -DskipTests` first (the generator
  reads the schema from the `.m2` yaml jar); `./mvnw -q -pl tesseraql-docs-reference compile
  exec:java` (both reference pages + `reference-config.md`); the scaffold regeneration
  (`-Dtesseraql.scaffold.regenerate=true` in the reactor with `-am`), then revert the demo pom's
  `tesseraql.version` drift; `-pl tesseraql-yaml,tesseraql-compiler,tesseraql-runtime,tesseraql-cli,tesseraql-studio,tesseraql-maven-plugin,tesseraql-operations,tesseraql-host,tesseraql-docs-reference -am`.
- **5b**: `./mvnw -q -pl tesseraql-core,tesseraql-yaml,tesseraql-pipeline -am install -DskipTests`
  first; regenerate `reference-config.md` AND `reference-error-codes.md`; `-pl tesseraql-yaml,tesseraql-compiler,tesseraql-pipeline,tesseraql-operations,tesseraql-runtime,tesseraql-cli,tesseraql-host,tesseraql-docs-reference,tesseraql-studio -am`.
  No schema edit: `tesseraql-maven-plugin` stays out.
- **5c**: `-pl tesseraql-core,tesseraql-pipeline,tesseraql-operations,tesseraql-runtime,tesseraql-docs-reference,tesseraql-cli,tesseraql-studio -am`;
  after a real `-am install` of core/yaml, `exec:java` and `git diff --stat docs/reference-*` must
  be EMPTY; `git grep zipName` must hit only `SplitExport.java`, `SplitExportZipNameTest.java` and
  the docs.

Every PR: `./mvnw spotless:apply` before the final verify; the bracket re-run from the PR's
source AFTER the final spotless with the build stamp checked; `git status --porcelain` empty of
anything but the PR's files.

---

## What this breaks

Recorded, no migration steps (pre-1.0).

- **5-0.** A DuckDB `time` cell changes from `22:30` (`LocalTime.toString()`) to `22:30:00` on
  csv/pdf and from a text cell to a time cell in a workbook; a Thymeleaf print template that
  received a DuckDB `LocalTime` object now receives the `String` `22:30:00` (`#temporals` on it
  throws — no shipped template selects a time). `format:` tokens on a time column are Excel's in
  a workbook (`hh:mm`) and Java's on csv/pdf (`HH:mm`) — the pre-existing asymmetry of every typed
  column, now reachable on a time. A `format:` naming a date field on a time column throws at the
  first cell on csv/pdf (500 / FAILED — misfiled as `TQL-SQL-2500` until 5b) while a workbook
  silently shows `1899/12/31 22:30`; there is no `type: time` for 5a to judge it by. A MariaDB
  `TIME` outside a day renders wrapped at 200 (the driver's doing).
- **5a.** A bad literal refuses boot, `job run` and a hot reload where it 500'd / FAILed /
  completed in ROOT; a missing workbook or print template refuses boot (a 500 `TQL-LD-2856`/`2831`
  per request today); `format: Excel` refuses on file-export and job steps too; new lint ERRORS on
  previously silent shapes (`sheet:`/`startCell:`/`template:` on csv, `locale:` on excel, an
  unknown export `type:` — all inert at boot; a source on a job step or a poll job; an undeclared
  `query./params.` input; `body.<undeclared>` under the default policy; `query./params./body.` on a
  file-import; `query..x`; a mixed-case `format:`); a bad `tesseraql.files.*` literal refuses boot
  even in an export-less app and refuses a hot reload as a whole; a boot WARN per inert/advisory key
  (the BOM IT's fixture shows one); `file-export` without `format:` works as csv; `TQL-FIELD-4622`
  no longer fires on an excel export; `ExportRules.UNUSABLE_EXPORT_TEMPLATE` is deleted; every new
  compile-time refusal class lands in `catalog.json` on a refused hot deploy (m8 RUN: the refused
  version becomes active and the next cold start fails wholesale — the runtime-replace campaign's
  to fix, this record's to say).
- **5b.** Every route with a `locale:`/`timezone:` source and no value, every job export step and
  every poll-triggered import, in an app that sets `tesseraql.files.*`: renders in the configured
  zone/locale instead of the JVM's (none shipped). Every caller sending a bad `?tz=`/`?loc=`/claim:
  400 where they got 500 / FAILED / ROOT — a client that retried on 5xx stops retrying. A synchronous
  export's document-write failure is `TQL-LD-2802` where clients saw `TQL-SQL-2500` (an alert keyed
  on `SQL-2500` for exports stops firing for those; it still fires for SQL). Three package-internal
  binder constructors and `PollSources`'s constructor change type; `JobExecutor` gains an optional
  setter; `FormatSources.resolve(Exchange, String)` is deleted; the legacy test is renamed.
- **5c.** A `file-export` split transfer's status JSON `filename` is the bundle (`orders.zip`),
  not the pattern; the ops-console transfers row reads `zip`; a `push:` step with no `as:` delivers
  `orders.zip`; a mail `attach:` of a split transfer attaches `orders.zip`/`application/zip`;
  `steps.<id>.filename` of a split step is the bundle's name — each the defect's cure seen from a
  consumer. A job step's `filename:` with a literal `{key}` and no `splitBy:` renders `r-{key}.csv`
  (was `r-.csv`); a push `as:` carrying `{key}` delivers the literal. A split that fails before
  bundling records `zip`/`<stem>.zip` on its FAILED row. Rows recorded before 5c keep their pattern
  name and codec format and are served exactly as before.

---

## Filed, not fixed

Every unfiled defect the measurement found, routed elsewhere with its destination (MEASUREMENT
§3, §6, and the four syntheses).

- **Temporal semantics (its own design, merged with result-column-types):** N2's fix (the
  zoneless-`timestamp` semantics by `ResultSetMetaData` type, the DST-gap shift, the three codecs'
  disagreement, the `.0` vs `T…Z` per-driver split); `microsoft.sql.DateTimeOffset`,
  `oracle.sql.TIMESTAMP*`; PostgreSQL `timetz` host-zoned by pgjdbc (`13:30:00` on a UTC host for
  a stored `22:30+09`); an `OffsetTime` on a buffered surface (`TQL-LD-2853` from
  `SpooledRows.write:307`); `uuid`/`jsonb`/`bytea` on csv and the spooled 2853; the sub-second
  divergence (`time(3)` → `22:30:00` on five dialects, `22:30:00.5` on DuckDB; `HH:mm:ss.SSS` on a
  `java.sql.Time` prints `.000`); MySQL refuses / MariaDB wraps a TIME outside a day; the JSON path
  (`22:30`) vs the export (`22:30:00`) on a zero-second time; `ColumnValues.locale`'s lenient parse.
- **Export hygiene:** #9's reason projection (`exitMessage` into the status JSON as a
  transfer-level `reason`, the card, `TransferSummary`, the console link, `bindFinish` truncation,
  the null-message fallback, a TQL code on the async reason — a documented wire-shape change); the
  drain-spool and writer-spool leaks on every surface (fix at `SpooledRows.drain`/`ExportWrite.write`;
  the test must fail a BUFFERED codec during its drain and count `tql_temp_spool` rows under
  `temp.store: db`); the Excel `format:` string written verbatim as the cell format and `type: date`
  getting the datetime default cell format; `sheet:` name sanitising; `groupBy` with a pdf template
  silently `GROUPS=null`; the PDF template's `Locale.ROOT` context (`PdfTemplates.java:33,38`); the
  `TQL-LD-2856` missing-template code never naming the file; the `'null'` format text; the zero-row
  0-byte csv (#24); the 32,768-char cell trio (#22); `TQL-LD-2801`'s hard-coded excel hint; the
  5311/5312 wrong-reason lints; the caller's principal in `params_json`; "make the jxls report mode
  honour the zone" (hand jxls a `LocalDateTime` in the declared zone — after the temporal decision);
  jxls report mode's `25569.9375` for a `java.sql.Time`; the `TQL-LD-2802` message naming the format
  and file, not the column; a codec `UncheckedIOException` raised OUTSIDE the write keeping
  `TQL-SQL-2500`; the ZIP host byte, the surrogate split in `SplitExport.safe`, the masked 2857, the
  two-part extension in `zipName` (`orders-{key}.tar.gz`); `TQL-LD-2810: Export step failed:
  TQL-LD-2858: …` double-wrapping; the FAILED-split row cosmetic.
- **F82 slice 2:** any change to codec discovery — `FileCodecs.discover()` in `buildQueryExport:1388`
  (the TCCL defect: the shipped `users/print` cannot boot on the CLI classpath even with
  `tesseraql.modules` declared), adding `require(format)` to `buildFileExport` (it would turn the
  working module-channel file-export into boot refusals), an unknown-format lint by discovery; no
  runtime IT exercises a query-export through the module loader.
- **Lookups record / export hygiene:** `TQL-FIELD-4622` guarding nothing on any export surface,
  the dead `CatalogBinder(fixedLocale)` step on export routes, `docs/lookups.md` decision 12's
  Export row, the #742 CHANGELOG framing; the two `CatalogBinder` sites' collapsed
  `formatDeclaration` — do NOT extend 4622 to jobs.
- **Base-path emission ledger:** the status JSON's `fileUrl` drops the base path
  (`FileTransferStatusProcessor:94`; `FileTransferIntegrationTest:138` asserts `endsWith` only).
- **Slice 8 / N1 (i18n):** `tesseraql.i18n.defaultLocale: ja_JP` (or a `locales[]` entry) normalizes
  to `und` and turns every error response on the app into a bare 500 `TQL-ROUTE-5000`;
  `messages.js?locale=und` hangs 300 s then 502 through the gateway; `tesseraql lint` crashes when
  `messages/` exists. Until then `request.locale` can carry `und` and 5b passes it through.
- **Lint hygiene / sweeps / audit-hardening:** `timezone: principal.subject` / `principal.roles`
  answers 400 blaming the caller — restrict `principal.` sources to `principal.claim.*` in 5a's
  `sourceViolation`; a declared input's `default:` (`input: tz: {default: Asia/Tokio}` +
  `timezone: query.tz`) is judged as the caller's — judge `input.<name>.default` when a
  `query.<name>` source names it; a file-import route without an `import:` block NPEs at
  `definition.rowStep().file()` before the predicate; `after:` on a query-export is lint-silent and
  boot-3101; a lint WARNING for `{key}` in `push.as:`; `conditions.zone` and bad-cron unshaped boot
  refusals; the lint crash on a header-less document; TQL-YAML-1409 defined twice; the SEC-4048
  (`[]`, `""`, `[""]`) and PolicyCodes lint/boot drifts; the header-fed input
  (`RequestBinder.rawValue:348` vs `vertx-native.md:307`); `EvaluationContext`'s reflective reach
  (`principal.toString` echoes every claim into the ERROR log); `body.*` on a GET.
- **Runtime-replace campaign:** a refused hot deploy advances `catalog.json`; `deploy rollback`
  targets the refused version and leaves `previous` null — 5a widens the refusal surface it must
  survive.
- **HTTP edge:** GET with a form content-type through the gateway → raw 500 from `BodyHandler`;
  HTTP/1.0 mid-body close reading as a complete file; `dev` loading manifests outside its
  `TqlException` catch.
- **Studio backlog:** `renderExportPdf:480` builds its spec without `withFormatting`.
- **Nowhere:** a mid-stream truncation guard — structurally impossible for exports (spool-first).
- **5a's own vocabulary question:** whether to add `time` to the `type:` vocabulary (a schema edit)
  so a date-bearing `format:` on a time column can be judged — decided NOT in this slice; recorded
  above under "What this breaks" for 5-0.

---

## Traps and guard hazards this design added to the measured thirty-five

- **36 (5b).** PostgreSQL's first `nextval` on a fresh sequence returns 1 and leaves `last_value`
  at 1 — a "sequence unchanged" guard reading `last_value` alone is green on a codec-placed refusal
  when it runs first, which JUnit's name-hash order made it do. Read `(last_value, is_called)`,
  prime the sequence, and run the refusal method first.
- **37 (5-0).** A zone-flip guard that flips the JVM zone but declares no zone on the grid route is
  A2-blind: the IT was 8/8 green on A2 under both zones until the grid declared `timezone: UTC`.
  The declaration is inert on a time column by design and must stay.
- **38 (5-0).** An `OffsetTime` fixture whose offset equals the export zone's is blind to a shift
  into the export zone (the design's `+09:00` under Tokyo saw nothing); keep the offset ≠ the zone.
  An ISO-text fixture with non-zero seconds (`22:30:05`) passes through on HEAD; use zero seconds.
- **39 (5a).** `-Dtest='A+B'` selects nothing (use commas); the bracket must be re-run AFTER the
  final spotless because its anchors are the formatted text; a variant applied to a pre-spotless
  tree silently patches nothing — verify every anchor ×1 (`bracket.py check`).
- **40 (5a).** A compile fixture on `format: pdf` or `excel` in `tesseraql-compiler` is green on
  `TQL-LD-2801` for `isInstanceOf(TqlException)` alone (csv-only test classpath) — except on
  file-export, which looks no codec up; keep one `format: pdf` query-export fixture as the "before
  `require(format)`" pin (V5) and every other fixture on csv.
- **41 (5a).** A boot WARN asserted by substring is green on pdfbox's font WARNs and DEBUG YAML
  dumps — capture stderr around `start`, assert the exact line with route id and key, and its
  absence on the valid twin.
- **42 (5b).** `make-variants.py` recreates every variant directory; a bracket run after it without
  a rebuild runs every column as HEAD — the runner refuses a variant without a `BUILD-STAMP`.
- **43 (5b).** The runtime IT's config zone must be one no CI or developer host runs in
  (`Asia/Kolkata`, a half-hour offset) or the CONFIG rung is indistinguishable from the platform;
  the SOURCE locale must differ from both the platform and the config (`ja-JP-u-nu-fullwide`, not
  `en` on an `en_US` host).
- **44 (5b).** A javadoc placed between another javadoc and its method fails the reactor's
  `-Xlint:all -Werror` (dangling doc comment); the `JobExecutor` setter goes after
  `fileTransfers`'s body and before `filePush`'s javadoc.
- **45 (5c).** A job split guard over ONE group or ZERO rows is green on an erased `{key}`
  (zero rows: 2858 today, an empty ZIP after — red → green without proving entries); assert two
  entries by name over a `VALUES` list the sibling seeds cannot reorder.
- **46 (5c).** A `notes.zip` csv whose bytes do not begin with `PK` is blind to a type sniffed off
  the bytes; the fixture's first column is labelled `PK` on purpose.
- **47 (5c).** Classpath-shadow prototyping is not a build — every shipped guard was ALSO built by
  the reactor and run under surefire against six built variants.
- **48 (all).** A doc sentence composed in the record must be RUN through `lint-prose.mjs`
  before it ships (the 5a synthesis's own 61-word sentence was refused live); `sync-content.mjs`
  refuses "slice N" on a published page — the record and `download-name-and-bytes.md` are exempt,
  `file-transfers.md`/`jobs.md` are not.
- **49 (5a/5b).** The two `Optional<String>` problem methods and `bounded()` are the ONE seam
  between 5a and 5b: a 5a change to what `bounded()` strips or how it ends turns 5b's U13 red in
  5b's PR — a legitimate cross-PR guard, fixed in 5a's helper, never by loosening U13.

---

## Recorded deviations from the thirteen default decisions

- **Decision 7(ii) — the locale rule's language set** gains the CLDR alias net (decision 15; open
  question U1).
- **Decision 7 — "one rule for literals and request sources"** gains the underscore fold for a
  principal-sourced LOCALE (decision 30; open question U2).
- **Decision 7 — "blank = platform default"** is read as "blank is unset, falls to the key"
  (decision 29) — HEAD's own reading for a blank literal.
- **Decision 4 — "400 naming the claim"** is rendered as two claim-specific message keys and the
  whole expression as `field` (decision 32) — the letter of "the message must say claim".
- **The scope's "unknown `columns[].type` refused"** is INERT 1005 on an export, INVALID 1063 on an
  import (decision 13) — decision 2's own rule applied to a key the export ignores.
- **Decision 10 — template existence at boot "on the route arms"** is judged on the job arm too
  (`requireJob` resolves beside the job file) and on the formats that read a template only.
- **The measurement's "the CLI-level shape is disclosed as unguarded"** — the `JobCommand`
  refusal shape is now RUN and guarded; only the `dev`/`host` one-line/exit-2 rendering stays READ.

---

## Settled after the four syntheses were written (the cross-PR adjudication)

1. The predicate's name, package and public surface (decision 2): 5a's `ExportDeclarations`; the
   two `Optional<String>` methods extracted so 5b's `RequestFormats` calls them. RUN
   `adjudication/probe/locale-rule-parity.log`: 0 mismatches on 5b's 28 fixtures.
2. `bounded()` (decision 3): 5a's implementation; 5b's U13 re-asserted (`...`, ≤ 43). RUN
   `adjudication/probe/bounded-parity.log`.
3. `FormatSources` line numbers: at HEAD the file is 58 lines — `resolve(Exchange, String)` `:23-36`,
   the pure overload `:39-53`, `isSourceExpression` `:55-57` (5b's synthesis cited `:108-110`).
4. The shared schema description: edited once by 5a in its final form (decision 41); 5b's OQ7 stands.
5. The `file-transfers.md` bullet: composed once here; 5a writes all but the chain sentence, 5b
   replaces that sentence (5b's replacement text would have erased 5a's).
6. The 2810 row: 5b's (5a's base text dropped a live meaning).
7. `JobCommand.wire()` and the config keys: 5a's `declarationsHold` judges them (decision 12), with
   the guard and variant SL2 to be built and proven red before 5a ships; 5b's OQ8 closes.
8. The audit-record amendments: facts by 5-0, status per PR (decision 43).
9. 5c's statement that "5b edits `JdbcFileTransferService`" is wrong: 5b's file-export refusal is in
   `FileExportStartProcessor` (compiler); the two PRs share `SqlStep.java`, `ExportStepRunner` is
   5b's only, `JdbcFileTransferService` is 5c's only.
10. Line anchors after each merge: 5a's `RouteCompiler` (`:41`, `:138`, `:1387`, `:1447`,
    `:1548-1557`) shift 5b's (`:70`, `:127`, `:1427-1432`, `:1491`, `:1580-1581`); 5a's
    `TesseraqlRuntime` (`:1346-1351`, `:1434`) and `JobCommand` (`:148-215`) shift 5b's (`:1157`,
    `:1185`, `:1703`; `:435`); 5b's `SqlStep` (`:46`, `:280-290`, `:346-349`, `:549`) shifts 5c's
    (`:362-372`, `:434-446`); 5-0's `file-transfers.md` bullet shifts 5a's `:119-125` by five
    lines. Each checklist re-anchors on the merged text before editing.

---

## Read the decisions, not the plan

The plan's sentences that survive are the four PR titles and the order. Everything else — the
code, the altitude, the three defects, the chain, the job arm, the shape of the refusal, the
existence of 5-0 and 5c — is settled by the decisions above and the evidence they cite. When a
later change touches an export declaration, start from decision 2 (the one predicate) and decision
28 (the one chain); when it touches a cell's rendering, start from "Today's temporal contract" and
the temporal-semantics design that owns it.
