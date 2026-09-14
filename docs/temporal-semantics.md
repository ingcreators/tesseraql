# Temporal semantics: a column's kind comes from the database, and every path renders it the same way

> **Status: in progress.** All thirteen decisions taken by the user on 2026-09-14, as
> recommended. **T0** — the read seam (`JdbcValues`), `ResultRows.value` canonical and
> allow-listed, the route and transition readers through it: shipped as T0. **T1** — the export
> reader through the seam, the untyped cell's SQL text, the legacy arms gone, the spool's two
> tags: shipped as T1. **T2**, **T3**: planned. The record merges the
> temporal-semantics design that [`export-declarations.md`](export-declarations.md) decision 13
> deferred (the typed zoneless-`timestamp` shift, the three semantics across five dialects, the
> Oracle object hash) with the result-column-types design of 2026-09-10 (the `jsonb` bean leak,
> the dependency-free allow-list in core, a later `result:` declaration). Four slices, T0-T3,
> each a pull request from fresh `origin/main`; T0 first because its defect is a loud 500 on a
> shipped analytics stack. **Read the decisions, not the filings** — the filings under-stated
> every item, and the most severe defect is in none of them.
>
> **What measurement added on 2026-09-14** (all RUN, this machine, `work/temporal-semantics/`):
> (1) **every DuckDB JSON route that selects a `date`, `time` or `timestamptz` column answers
> 500 `TQL-ROUTE-3001`** — the driver hands the framework a `java.time` value and the JSON mapper
> has no module for it; MySQL `DATETIME` and H2 `timestamptz` are the same mechanism. (2) The
> JDBC 4.2 `java.time` reads — `getObject(i, LocalDateTime.class)` for a zoneless column,
> `OffsetDateTime` for a zoned one — are host-independent and DST-gap-proof on all five supported
> drivers, and they turn Oracle's `TIMESTAMP WITH TIME ZONE` object hash into a correct instant.
> One rule, one seam, then fits every dialect. (3) PostgreSQL's zoneless `timestamp` is served on
> JSON as a UTC instant with a `Z` it never had; `timetz` `22:30+09` is served as `"13:30"`.

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` with this record,
the registration asserted in `InternalDocsSyncTest`.

---

## The measured premise

### The drivers — what a temporal column is, and what the `java.time` reads give

`TemporalProbe` (`work/temporal-semantics/probe/`, `MATRIX.txt`) ran the same SELECT over the
temporal column types of each supported dialect — pgjdbc 42.7.13 / PostgreSQL 16, Connector/J
26.7.0 / MySQL 8.4, mssql-jdbc 13.4 / SQL Server 2022, ojdbc11 23.26.3 / Oracle Free 23,
duckdb_jdbc 1.3.1 — twice, under a UTC JVM and a `America/Los_Angeles` JVM, and printed for each
cell the JDBC metadata, the driver's default `getObject(i)`, and each of the five `java.time`
reads (`LocalDateTime`, `OffsetDateTime`, `LocalDate`, `LocalTime`, `OffsetTime`). Row 2 sits in
the Los Angeles DST gap (`2026-03-08 02:30`), the shape slice 5's refuter found shifting.

| column kind | dialects, JDBC type code, `getObject(i)` class | the read that is right on every host |
|---|---|---|
| **wall clock** (no zone) | PostgreSQL `timestamp` 93 → `Timestamp`; MySQL `DATETIME` 93 → `LocalDateTime`; SQL Server `datetime2`/`datetime` 93 → `Timestamp`; Oracle `TIMESTAMP` 93 → `oracle.sql.TIMESTAMP`, Oracle `DATE` 93 → `Timestamp`; DuckDB `timestamp` 93 → `Timestamp` | **`LocalDateTime`** — identical on both hosts on all five; the DST-gap row reads `02:30` where `getObject` reads `03:30` on the Los Angeles JVM (pg, mssql, duckdb) |
| **instant** (zoned) | PostgreSQL `timestamptz` **93** (only `getColumnTypeName` = `timestamptz` tells it apart) → `Timestamp` host-shifted; SQL Server `datetimeoffset` −155 → `microsoft.sql.DateTimeOffset`; Oracle `TIMESTAMP WITH TIME ZONE` −101 → `oracle.sql.TIMESTAMPTZ` (an object hash); Oracle `WITH LOCAL TIME ZONE` −102 → `TIMESTAMPLTZ`; DuckDB `timestamptz` 2014 → `OffsetDateTime` | **`OffsetDateTime`** — the same instant on both hosts on all five (Oracle LTZ carries the session offset, same instant) |
| **date** | all: 91 → `java.sql.Date` (DuckDB `LocalDate`) | **`LocalDate`** |
| **time of day** | PostgreSQL/MySQL/SQL Server `time` 92 → `java.sql.Time`; DuckDB `time` 92 → `LocalTime` | **`LocalTime`** — DuckDB refuses the typed read but its default is already `LocalTime` |
| **time with zone** | PostgreSQL `timetz` **92** → `java.sql.Time` host-shifted (`13:30` / `05:30` for `22:30+09`); DuckDB `timetz` 2013 → `OffsetTime` | **`OffsetTime`** — `22:30+09:00` on both hosts |

Two traps the seam must respect. pgjdbc reports `timestamp` and `timestamptz` under the same
code 93, and reading a zoneless column *as* `OffsetDateTime` succeeds on pgjdbc (as UTC) and on
Connector/J (with the JVM's offset — a different instant per host): so for code 93 the order is
`LocalDateTime` first, and only a driver that refuses it (pgjdbc for `timestamptz`) is read as
`OffsetDateTime`. And a driver may refuse a typed read while its default is already the right
class (DuckDB `LocalTime`, `OffsetTime`): the fallback is `getObject(i)` with the legacy
`java.sql.*` classes converted (`toLocalDateTime()`, `toLocalDate()`, `toLocalTime()`).

MySQL `TIMESTAMP` (an instant stored as UTC, presented by the server in the session zone) reads as
a `LocalDateTime` wall clock in the session zone on both hosts — stable, but a wall clock. Read as
`OffsetDateTime` the connector attaches the JVM's offset (`22:30Z` / `22:30-08:00`), a different
instant per host. Decision 12 treats it as what the connector presents.

### The JSON mapper

Jackson 2.22.2 as pinned, no `jackson-datatype-jsr310` anywhere in the repository (`JacksonProbe`,
`logs/jackson.log`): `LocalDateTime`, `LocalDate`, `LocalTime`, `OffsetDateTime`, `OffsetTime`
and `Instant` each throw `InvalidDefinitionException` ("not supported by default"); `UUID`,
`byte[]` and `BigDecimal` serialize. So any `java.time` value that reaches a JSON body is a 500.

### The JSON path today (RUN, `TemporalJsonProbeIntegrationTest`, `logs/json-probe.log`)

A `query-json` route per column, the real runtime, a UTC JVM:

| column | answer |
|---|---|
| PostgreSQL `timestamp` `22:30:00.123456` | `"2026-01-15T22:30:00.123456Z"` — a wall clock served as a UTC instant (`ResultRows.value`: `Timestamp.toInstant()`, the JVM zone); on a Tokyo host the same row is `"…T13:30:00.123456Z"` |
| PostgreSQL `timestamptz` `22:30+00` | `"2026-01-15T22:30:00.123456Z"` — correct |
| PostgreSQL `date`, `time` | `"2026-01-15"`, `"22:30"` (`LocalTime.toString` drops the zero seconds) |
| PostgreSQL `timetz` `22:30+09` | **`"13:30"`** — converted to the JVM zone, offset dropped |
| PostgreSQL `uuid` | `"a0eebc99-…"` — fine |
| PostgreSQL `jsonb` | **`{"type":"jsonb","value":"{\"sku\": \"A-1\"}","null":false}`** — the `PGobject` bean; a SQL NULL is JSON `null` here, but a `NULL` inside a non-null wrapper would be the bean with `"null":true` |
| PostgreSQL `interval` | **`{"type":"interval","value":"1 days 2 hours","years":0,…}`** — the `PGInterval` bean |
| DuckDB `timestamp` | `"2026-01-15T22:30:00.123456Z"` — the same wall-clock-as-instant |
| **DuckDB `timestamptz`, `date`, `time`** | **500 `TQL-ROUTE-3001`** — `OffsetDateTime`, `LocalDate`, `LocalTime` reach the mapper |

No DuckDB integration test selects a temporal through a JSON route (`DuckDbReadIntegrationTest`
and its siblings select names, categories and sums), which is how a shipped analytics stack
carries a 500 on its most ordinary dimension.

### The export path today (slice 5, RUN there; unchanged since)

From `export-declarations.md` "Today's temporal contract" and its measurement (`m3`, `a3`): the
export reader `ResultSetRows` hands the codec `getObject(i)` raw; `ColumnValues.toZoned` treats a
`java.sql.Timestamp` as `toInstant().atZone(zone)`, so `type: datetime` + `timezone: Asia/Tokyo`
on a zoneless PostgreSQL `timestamp` renders a stored `22:30` as `07:30` the next day on a UTC
host and `15:30` on Los Angeles; MySQL `DATETIME` (a `LocalDateTime`) keeps its wall clock under
the same declaration; Oracle `TIMESTAMP` is inert; Oracle `TIMESTAMP WITH TIME ZONE` is
`oracle.sql.TIMESTAMPTZ@4089713` in the cell; SQL Server `datetimeoffset` ignores the declaration.
Untyped cells are the driver's `toString()`: `2026-01-15 22:30:00.0` on pgjdbc,
`2026-01-15T22:30Z` on DuckDB for the same declared column. Any spooled export (pdf, a template,
`splitBy:`) refuses `uuid`, `jsonb`, `interval`, `OffsetTime` and the vendor temporals with 500
`TQL-LD-2853`.

### The consumers

Fourteen reader sites take user data off a `ResultSet` with `getObject(i)` (`git grep` on
`fd88bf61e`): `SqlStatement` (the route reader through `ResultRows.value` `:474`, the OUT
parameters `:857`, `:370`), `TransitionExecutor` (through `ResultRows.value`), `SqlStepRunner`
×2 (batch step), `ChunkRows` (keyset/chunk), `KeyedReference` (enrich), `LookupReferences` ×2,
`DecisionTables`, `ValidationRules`, `SqlStep:483` (the scalar), `ResultSetRows` (export),
`StudioTestService`, `StudioDataService` ×3, `SuiteContext`. Framework-owned tables
(`JobRepository`, `JdbcRouteAuditStore`, `OraclePlanInspector`) read their own columns and are
out of scope.

`ResultRows.value` (`core/dialect`) is the one normalizer the bindable paths share: it turns
`java.sql.Timestamp/Date/Time` into ISO text and passes everything else through — the
`java.time` values, the `PGobject`, the `PGInterval`. Its own Javadoc records that Studio, the
suite runner and the reference lookup "do not ask here at all".

`SpooledRows` carries `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `OffsetDateTime` and
the three `java.sql.*` classes (tags 10-17), `byte[]` (18), long strings (19); not `OffsetTime`,
not `UUID`. `ColumnValues.toZoned` has arms for `LocalDateTime` (`atZone`, the wall clock kept)
and `OffsetDateTime` (`atZoneSameInstant`) — the right semantics, reachable today only from
Connector/J and DuckDB.

`tesseraql-core` is dependency-free by an enforced rule (`core-is-dependency-free`), so nothing
here may put Jackson, a driver class or a `jsr310` module into core.

---

## The rule

**A column's kind is what the database says it is, the framework reads it in that kind, and every
path renders one canonical form per kind.** Concretely: a reader asks the JDBC metadata, reads a
wall clock as `LocalDateTime`, an instant as `OffsetDateTime`, a date as `LocalDate`, a time of
day as `LocalTime`, a time with zone as `OffsetTime`; a wall clock is never moved across zones
and an instant is presented in the zone the surface declares; anything the framework does not
know is its text. The host's zone appears nowhere.

## The decisions

Numbered for the user; the recommendation is the sentence in bold. "BREAKING" marks a wire change
recorded in the CHANGELOG with no migration (the pre-1.0 rule).

1. **A zoneless SQL `timestamp`/`datetime` is a wall clock: read as `LocalDateTime`, never
   converted, and emitted on JSON as `2026-01-15T22:30:00.123456` — no `Z`.** BREAKING: today's
   `…Z` on a UTC host. The alternative — keep treating it as an instant in some zone — needs a
   zone nobody declared; the JVM's was the defect. A consumer that needs an instant selects an
   instant column or casts in SQL.
2. **An instant column is read as `OffsetDateTime` and emitted on JSON normalized to UTC with
   `Z`** (`2026-01-15T22:30:00.123456Z`) — byte-identical to today for PostgreSQL on a UTC host,
   and host-independent everywhere (Oracle LTZ's session offset folds away). Alternative: keep
   the driver's offset (`+09:00`) — stable only per driver.
3. **Canonical JSON text always prints seconds** (`T02:30:00`, never `T02:30`) and prints a
   fraction only when non-zero, at the driver's precision — today's `Instant.toString()` shape,
   which `LocalDateTime.toString()` does not keep. `LocalTime` the same (`"22:30:00"`, today
   `"22:30"` — BREAKING, small).
4. **A time with zone is read as `OffsetTime` and emitted as `22:30:00+09:00`** — BREAKING:
   today's host-zoned `"13:30"`. A `LocalTime` is emitted as `22:30:00`.
5. **Untyped export cells render one SQL-style text per kind**: `2026-01-15 22:30:00.123456`,
   `2026-01-15`, `22:30:00`, `22:30:00+09:00`; an untyped instant is presented in the export's
   effective zone (declared `timezone:` → `tesseraql.files.timezone` → the platform default) and
   printed as that zone's wall clock. Byte-identical to today for PostgreSQL wall clocks and for
   PostgreSQL instants when the host is the export zone; BREAKING for DuckDB/H2 untyped instants
   (`2026-01-15T22:30Z` → `2026-01-16 07:30:00` under Tokyo) — which closes the audit's item 2,
   `timezone:` inert for an untyped column, in the direction of taking effect.
6. **`timezone:` converts instants only; a wall clock is printed as stored, `type: datetime`
   formats it without conversion.** This is the fix for the headline (`22:30` → `07:30`/`15:30`):
   the `toZoned` arms already do it once the reader hands the right class; the
   `java.sql.Timestamp` arm (`toInstant().atZone`) is deleted, not kept for "other drivers" — the
   seam converts the legacy classes before any codec sees them. `export-declarations.md`'s
   "Today's temporal contract" is superseded by this record.
7. **A value the framework does not know is its text on every bindable path** (result-column-types
   layer 1): `ResultRows.value` allows `String`, `Boolean`, `Number`, `byte[]`, `UUID` and the
   five `java.time` kinds through (the temporals as canonical text), and turns anything else into
   `String.valueOf(value)`. `jsonb` becomes the JSON text as a string (`"{\"sku\": \"A-1\"}"`),
   `interval` becomes `"1 days 2 hours"`. BREAKING for the bean dumps, which nothing can have
   depended on. Parsing `jsonb` into a JSON value is T3's `type: json`, not this.
8. **The spool gains `OffsetTime` (tag 20) and `UUID` (tag 21)**; an old spool still reads. With
   decision 7 no vendor object reaches the spool, so `TQL-LD-2853` narrows to a genuinely unknown
   class.
9. **Every reader of user data reads through the one seam** — the fourteen sites, Studio and the
   suite runner included — so `docs`' "do not ask here at all" stops being true. The suite
   runner's `looselyEqual` then compares canonical text: a suite expecting `…Z` for a wall clock
   changes with the wire (34 test files carry a `Z`-suffixed literal on `fd88bf61e`; how many are
   wire expectations rather than `Instant.parse` fixtures is measured when T0 runs the full
   verify).
10. **Oracle `DATE` is a wall clock with a time part** (JDBC 93): `LocalDateTime`
    (`2026-01-15T00:00:00`), and `type: date` on it formats the date part — Oracle's own
    semantics, not a `LocalDate`.
11. **The `result:` declaration (json/date/number by domain) is T3, designed after T0-T2 ship** —
    its nine open questions (the input vocabulary's `type: json`, the invalid-value rule, restated
    `format:`, the un-lintable absent column, bare-string shorthand kept in step with
    `field-domains.md`) are the 2026-09-10 list, unchanged, and none blocks T0-T2.
12. **MySQL `TIMESTAMP` is what Connector/J presents: a wall clock in the session zone** — documented
    under the dialect notes, not special-cased; an application that needs an instant on MySQL
    uses `DATETIME` written in UTC or sets `connectionTimeZone` on the datasource.
13. **The seam lives in core as `JdbcValues.read(ResultSet, int, ResultSetMetaData)`**, beside
    `ResultRows`; per-dialect knowledge is the JDBC type code and the refusal fallback, never a
    driver class name — core stays dependency-free.

### The slices, in build order

| # | pull request | closes | modules |
|---|---|---|---|
| T0 | the read seam and the bindable paths: `JdbcValues`, `ResultRows.value` canonical + allow-list, the route/transition readers | the DuckDB/MySQL JSON 500; the wall-clock `Z`; `timetz` host-zoning; the `jsonb`/`interval` beans | core, pipeline |
| T1 | the export path: `ResultSetRows` through the seam, `ColumnValues` untyped rendering, the deleted `java.sql` arms, the spool's two tags, the Excel and PDF cell arms | the typed zoneless shift; Oracle TSTZ hash; SQL Server `datetimeoffset` inert; the DST-gap cell; `uuid`/`OffsetTime` on spooled exports | core, excel, pdf, operations |
| T2 | the other readers: batch step, keyset/chunk, enrich, lookups, decision tables, validation, the scalar, Studio ×4, the suite runner; `java.time` bound back into SQL where a step re-binds a value | decision 9 | operations, compiler, yaml, studio-runtime, test-core |
| T3 | `result:` declarations (design first, in this record) | result-column-types layer 2 | yaml, compiler |

T0 → T1 → T2 in order (T1 and T2 read through T0's seam); T3 after its own decisions.

---

## T0 — the read seam and the bindable paths

Shipped. Bracket (`work/temporal-semantics/t0/`): HEAD `99d318820` — the four integration guards
red with the predicted shapes (the DuckDB 500, the wall clock's `Z`, the `jsonb` bean, the
zero-seconds `Z`), five of six `ResultRowsTest` rows and `SqlStatementTest`'s temporal row red;
`V-offset-first` — exactly the two TIMESTAMP order tests; `V-no-fallback` — exactly the DuckDB
time test; `V-seconds` — exactly the seconds and legacy rows; `V-passthrough` — exactly the
vendor-text row; the fix — 9/9, 6/6, 4/4 green. One deviation from the plan: the seam is
`JdbcValues.reader(metaData)` returning a per-result-set `Reader`, so a refused typed read is
remembered per column rather than paid once per cell.

### The change

- `io.tesseraql.core.dialect.JdbcValues.read(ResultSet rs, int i, ResultSetMetaData md)`:
  by `md.getColumnType(i)` — 91 → `LocalDate`; 92 → `LocalTime`, refused → `OffsetTime`, refused
  → `getObject` converted; 93 → `LocalDateTime`, refused → `OffsetDateTime`, refused →
  `getObject` converted; 2013 → `OffsetTime`; 2014, −101, −102, −155 → `OffsetDateTime`, refused
  → `getObject`; anything else → `getObject(i)`, with a `java.sql.Timestamp/Date/Time` that still
  arrives converted to its `java.time` kind. "Refused" is a `SQLException` from the typed read;
  the fallback order is fixed by the two traps above.
- `ResultRows.value`: `LocalDateTime`/`OffsetDateTime`/`LocalDate`/`LocalTime`/`OffsetTime` →
  canonical text (decisions 1-4, one formatter per kind, seconds always); `String`, `Boolean`,
  `Number`, `byte[]`, `UUID` through; the `java.sql.*` arms deleted (the seam converts them);
  everything else `String.valueOf`.
- `SqlStatement` (`:370`, `:474`, `:857`) and `TransitionExecutor` read through the seam.

### The guards, red before the fix

`TemporalJsonIntegrationTest` (runtime; PostgreSQL + in-process DuckDB; the probe promoted):
- a DuckDB route per `date`, `time`, `timestamptz` answers 200 with the canonical text (HEAD 500);
- a PostgreSQL `timestamp` answers `"2026-01-15T22:30:00.123456"` — and the same text after
  `TimeZone.setDefault(Asia/Tokyo)` in-test (the slice-5 rule: pgjdbc follows the default per
  call, so one test asserts two host zones; HEAD: `…Z` and `…T13:30…Z`);
- `timetz` answers `"22:30:00+09:00"` on both zones (HEAD `"13:30"`, `"22:30"`);
- `timestamptz` answers `"…Z"` on both zones (the control — byte-identical to HEAD on UTC);
- `jsonb` answers the JSON text as a string, `interval` its text (HEAD the beans);
- `LocalTime` seconds: `time '22:30'` answers `"22:30:00"` (HEAD `"22:30"`).
`JdbcValuesTest` (core; H2 in test scope only): every type code branch and both refusal
fallbacks through a proxy `ResultSet` that refuses a chosen class.
Variants: HEAD; V-offset-first (code 93 read as `OffsetDateTime` first — red on the wall-clock
row under Tokyo); V-no-fallback (DuckDB `time` red); V-seconds (`LocalDateTime.toString` — red
on the zero-seconds row); V-passthrough (`PGobject` still through — the `jsonb` row red).

### What this breaks

- Every JSON, HTML, workflow and view consumer of a zoneless column loses the `Z` and gains the
  seconds; a time with zone gains its offset; `jsonb`/`interval` become strings. The bundled
  apps' suites and the 34 files above are updated with the slice; each is a wire change the
  CHANGELOG records.

---

## T1 — the export path

Shipped. `TemporalText` (core/dialect) now holds both spellings — the wire's (`T`, an instant at
UTC) and the SQL one (a space, an instant in the export's zone) — and `ResultRows.value` uses
the former, `ColumnValues.format`'s untyped branch the latter. `ColumnValues.format`,
`toZoned` and `toLocalTime` convert a legacy `java.sql` value at entry (`JdbcValues.normalize`)
and the `java.sql.*` / `java.util.Date` arms are gone; the Excel and PDF writers needed no
change of their own, because their `toZoned` calls already had the right `LocalDateTime` and
`OffsetDateTime` arms and now receive those kinds. Slice 5's decision 12 ("an offset is dropped,
never applied") is superseded for a time with zone: the offset is printed, never applied.

Bracket (`work/temporal-semantics/t1/`): HEAD `b73201148` — the four export guards red with the
filed shapes (`07:30` for the typed wall clock under UTC; DuckDB `2026-01-15T22:30:00.123456Z`;
2853 on the split export; `timetz` `13:30:00`), four `ColumnValuesTest` rows and the spool's
round trip red; `V-keep-timestamp-arm` — exactly the legacy-wall-clock row; `V-no-zone-on-untyped`
— exactly the untyped-instant row; `V-no-spool-tag` — exactly the round trip;
`V-reader-getobject` (the export reader not through the seam) — the typed instant row, the
untyped row and the split export red; the fix — 12/12, 8/8, 4/4.

### The plan, as written

`ResultSetRows` reads through the seam (its Javadoc's "raw JDBC objects, deliberately" becomes
"the column's kind, deliberately"); `ColumnValues.format`'s untyped branch renders decision 5's
text through the export's effective zone; `toZoned` loses the `java.sql.*` and `java.util.Date`
arms; `SpooledRows` gains tags 20-21; `JxlsFileCodec.setCell` and the PDF grid take the five
kinds (a wall clock as a date cell without a zone shift, an instant converted to the export
zone). Guards: the slice-5 shapes re-run as assertions — `type: datetime` + `timezone:
Asia/Tokyo` on a zoneless `timestamp` renders `22:30` on UTC and on Tokyo hosts (HEAD `07:30` /
`22:30`), the DST-gap row renders `02:30` under a Los Angeles default (HEAD `03:30`), a
`timestamptz` renders `07:30` under Tokyo (the control), a spooled export of `uuid` + `timetz`
answers 200 (HEAD 2853); the five-dialect matrix as a gated dialect-suite test (the "Dialect
suites" workflow, dispatched manually after the change — the release-ritual rule). Variants: HEAD;
V-keep-timestamp-arm (the shift returns on any driver that falls back); V-no-zone-on-untyped
(the DuckDB untyped instant prints `T…Z`); V-no-spool-tag (`timetz` on pdf red).

## T2 — the other readers

Each site swaps `getObject(col)` for `JdbcValues.read`, and where the value is bound back into
SQL (batch step params, keyset boundaries, enrich keys) a `java.time` value binds through
`setObject` — JDBC 4.2 on every supported driver; the guard for each is the existing test of that
path with one temporal column added, red on HEAD only where the path re-binds the value (a
`LocalDateTime` where a `Timestamp` was) — the design expects most to be green-by-construction
and says so up front rather than claiming a red proof it cannot have. Studio's data browser and
the suite runner render canonical text; `docs/…`'s "do not ask here" sentence is deleted.

## T3 — `result:` declarations

Designed here after T0-T2 ship. The 2026-09-10 shape stands as the starting point: a sparse
per-source `result:` block whose values are `InputField`s (so `domain:` reuse is free), resolved
at compile time; `date`/`datetime`/`number` handled by `ColumnValues`; `type: json` installed
from outside core through an `ExpressionFunctions`-style hook, producing a `Map`-implementing
value whose `toString()` is canonical JSON so all six consumers agree. Its open questions are
decision 11's.

---

## Scope out, each with its destination

- The write direction (a `LocalDateTime` bound into a `timestamp` column; MySQL's refusal of a
  DST-gap `TIMESTAMP` insert on a Los Angeles JVM) → the batch/binding line; T2 measures the
  read-then-rebind paths only.
- `connectionTimeZone` / session-zone configuration per datasource → the datasource line
  (decision 12 documents, does not add a key).
- Excel's own rendering of a date cell in the viewer's locale → the Excel codec line.
- `time` in the `type:` vocabulary (slice 5's "5a's own vocabulary question") → T3.

## The audit records, amended by this design

- [`audit-medium-leads.md`](audit-medium-leads.md): item 2's temporal half gains this record as
  destination; a new item 26 — the DuckDB/MySQL JSON 500 — enters "Defects surfaced that the
  audit does not carry".
- [`export-declarations.md`](export-declarations.md): "Today's temporal contract" is marked
  superseded by this record; its "Filed, not fixed" temporal bullet points here.
- [`export-hygiene.md`](export-hygiene.md) P5's filed note (the types half of the metadata seam
  beside `NamedRows`) — `JdbcValues` is that half, on the reader rather than on `NamedRows`.
