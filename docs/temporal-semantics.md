# Temporal semantics: a column's kind comes from the database, and every path renders it the same way

> **Status: complete.** All thirteen decisions taken by the user on 2026-09-14, as
> recommended, and T3's nine (14-22) the same day, as recommended. **T0** — the read seam
> (`JdbcValues`), `ResultRows.value` canonical and allow-listed, the route and transition
> readers through it: shipped as T0. **T1** — the export reader through the seam, the untyped
> cell's SQL text, the legacy arms gone, the spool's two tags: shipped as T1. **T2** — the other
> eleven readers through the seam, the text surfaces through the bindable form: shipped as T2.
> **T3** — `result:` declarations on a route source and a command step, the `json`/`date`/
> `datetime`/`number` kinds through a domain, one lint-and-boot code for a kind a surface does
> not honour, one read code for text that will not parse: shipped as T3 (the precisions
> building it forced are under "T3 as built"). The record merges the
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

Shipped. Eleven sites (the twelfth, `SqlStep`'s `count(*)`, reads no user column): the typed
readers — `SqlStepRunner` ×2, `ChunkRows`, `KeyedReference` — read through the seam and keep
the kind; the text readers — `LookupReferences` ×2, `DecisionTables`, `ValidationRules`,
`StudioTestService`, `StudioDataService` ×3, `SuiteContext` — read through the seam and ask
`ResultRows.value` for the bindable form. `ResultRows`' Javadoc no longer names readers that
"do not ask here at all".

**The re-bind was measured, not assumed** (`work/temporal-semantics/probe/rebind/`,
`BindOne.java`, five drivers × two JVM zones): `LocalDateTime`, `OffsetDateTime`, `LocalDate`
and `LocalTime` bind through `setObject` on all five and round-trip as the same value — the
instant as the same instant, presented in the session zone where the driver normalizes
(pgjdbc, Connector/J, DuckDB) and with its offset where it does not (mssql-jdbc, ojdbc). The
one gap is DuckDB × `OffsetTime` ("Unsupported parameter type"), which predates the seam
(DuckDB already handed `OffsetTime` over from `getObject`) — a `timetz` re-bound as a keyset
boundary on DuckDB is filed.

Bracket (`work/temporal-semantics/t2/`): HEAD `7cf87ad27` — the suite expectation red
(`expected 2026-01-15T13:30:00Z but was 2026-01-15 13:30:00.0`) and Studio's browser row red
(the `Timestamp` text); the batch re-bind guard green on HEAD too, as the plan said it would be
(a `Timestamp` bound the same wall clock) — it guards the seam's bind, not a defect; the fix —
6/6, 1/1, 1/1. The lookup, decision-table and validation paths are rewired and exercised by
their existing tests, none of which carries a temporal column: green by construction,
disclosed rather than dressed as a red proof.

## T3 — `result:` declarations

**Status: shipped 2026-09-14; decisions 14-22 taken as recommended.** T0-T2 changed what
T3 is for. The seam gives every temporal its kind from the database's own metadata, so nothing
has to be declared to get a date, a wall clock or an instant right — the 2026-09-10 design's
`date`/`datetime` arms are done without a declaration. What a declaration still adds is
**parsing a value the database holds as text**: a `jsonb`/`json`/text column an author wants to
navigate (`payload.sku` in an expression, a template, a response binding) rather than receive
as one string (decision 7); a date or a number a legacy schema stores as text (`'20240103'`,
`'1,234.50'`); and, for those, the `domain:` vocabulary `input:` already has.

### The shape (unchanged from 2026-09-10 where it still applies)

A sparse `result:` block on a source, its values `InputField`s so a `domain:` is reusable and
`type`, `format` and the constraint keys mean what `field-domains.md` says they mean:

```yaml
sources:
  main:
    sql: { file: orders.sql }
    result:
      payload: { type: json }
      ordered_on: { domain: order_date }        # a domain saying "date written yyyy/MM/dd"
      amount: { type: number, format: "#,##0.00" }
```

Resolved at compile time, applied after the seam and before `ResultRows.value`: a declared
column's text is parsed into the declared kind (a `LocalDate`, a `BigDecimal`, a JSON value)
and then rendered exactly as a native column of that kind is — so a `date` stored as text and a
`date` column become the same case on every surface. Undeclared columns pass through as today
(sparse: the opposite default from `input:`, which rejects undeclared parameters).

`type: json` produces a value that is a `Map` (or a `List`) for navigation and whose
`toString()` is canonical JSON, so the five `String.valueOf` consumers print JSON text and the
JSON mapper writes the structure; the parser lives outside core (`tesseraql-yaml` upward,
where Jackson is) and installs through the same hook `ExpressionFunctions` uses — core stays
dependency-free (decision 13's rule).

### The decisions (taken 2026-09-14, as recommended)

14. **`type: json` is a read-only kind: legal in `result:` and in a domain, refused in `input:`
    with the existing TQL-FIELD code for an unknown type** — until a JSON input has a validation
    story of its own. Alternative: define input semantics now (parse the body field as JSON) —
    more than this slice needs.
15. **A value that cannot be parsed into its declared kind fails the read with one coded error
    naming the column, the row and the kind** (the silent-tolerance rule) — never silently passes
    the text through, which would flip a consumer's `payload.sku` from a value to null with no
    signal. One code for bad JSON and an unparseable date or number alike.
16. **A parsed text datetime is a wall clock** — a `LocalDateTime`, exactly what a zoneless
    column is under decision 1 — and a parsed date a `LocalDate`; there is no `timezone:` on a
    read declaration. The 2026-09-10 question (an instant from text?) is answered by the
    temporal rule: text has no zone, so it is not an instant.
17. **A `result:` entry may restate `format:` over its domain's** (the API takes ISO, the legacy
    column holds `20240103`): the restatement is neither tightening nor loosening, so the
    domain lint gains a third classification, "restated", which is not a finding.
18. **A domain's constraint keys (`maxLength`, `pattern`, `enum`, …) are not applied on read**,
    and the record and the reference page say so; validating what the database returned is a
    different feature.
19. **A declared column the query does not produce is a boot-time WARNING, not a refusal** —
    the columns of a `/*%if*/`-shaped query are not derivable, so the honest twin is the
    runtime: a declared column absent from every row of a response is logged once per route,
    never silently ignored, never a 500.
20. **The declaration applies on the bindable paths only** — the route reader, the workflow
    reader, the lookup, the decision table — where a value is navigated or rendered; the export
    reader keeps the `columns:` vocabulary it has, and the typed batch/keyset/enrich readers
    keep the kind the seam gives them. One declaration, one place it applies.
21. **No bare-string shorthand in this slice** (`result: [ordered_on]` meaning "a like-named
    domain"); `field-domains.md` open question 1 asks the same of `input:`, and the two stay in
    step by deciding neither alone.
22. **`ColumnSpec` (the export's `columns:`) is not folded into `InputField`** here; a later
    slice may unify the vocabularies once `result:` has shipped and the overlap is measured.

### T3 as built — where the decisions met the code

- **Where a declaration lives, and so where it applies (decision 20, made precise).** The
  declaration is a key on a `Binding` — `result:` beside the arm, like `enrich:`, because it is
  about the rows whatever fetched them — so it applies where a `Binding` publishes rows: a
  route's `sources:` (every recipe that mounts them, tools and prompts included) and a
  command's `steps:` in `mode: query`, whose `steps.<name>.rows` get the same application
  (`ResultDeclarationProcessor.apply`, one method for both). "The workflow reader, the lookup,
  the decision table" in decision 20's list have no binding to declare on: the workflow loads
  its document row by key, a `lookup:` borrows a route's SQL and reads three identifier
  columns, a decision table's outputs are typed by their own `domain:`. They keep the seam's
  kind and are not reached by a declaration; the sentence "one declaration, one place it
  applies" is the rule kept. An enrichment (`enrich:`) folds *into* declared rows after the
  declaration has run, so an enriched row's declared columns are already parsed.
- **Decision 14's "existing TQL-FIELD code for an unknown type" did not exist.** An
  `input:` with `type: json` — or `type: integr` — was bound as its raw text with no lint and
  no refusal (`InputBinder.coerce`'s default arm). One new code, `TQL-YAML-1064`, judged by one
  predicate (`DeclaredKinds`) at lint and at boot: a `type:` no request binds on `input:`, a
  kind outside json/date/datetime/number on `result:`, a `format:` the kind's parser refuses or
  one on `json`, a `result:` on a binding that publishes no rows (`update`, `call`, a sequence,
  a spool), and — lint only, jobs have no boot compile of their own here — a `result:` on a
  chunk reader or writer, which the typed batch readers never apply. The unknown-input-type
  refusal is new behaviour: an application carrying a typo'd type today boots; after T3 it does
  not, and says which field.
- **The read code is `TQL-SQL-2503`** (decision 15): the message names the source, the column,
  the 0-based row index, the value (bounded) and the kind; the wire body of a 5xx carries the
  code and the structured details (`source`, `column`, `row`, `kind`) and not the message — the
  error renderer's confidentiality rule — so the message is the log's.
- **The canonical form is always accepted** (decision 16, made precise): a `date`/`datetime`
  entry parses with its `format:` (the kind's default when absent) and, when that fails, tries
  the wire form (`2026-01-15`, `2026-01-15T22:30:00`). A native column declared for its domain's
  sake arrives canonical already (T0), and the declaration must not turn it into a 500.
- **A number pattern parses in the root locale**: `#,##0.00` reads `1,234.50`; a `locale:` on a
  read declaration is not in the decisions and is filed below.
- **The JSON value is a `Map`/`List` whose `toString()` is compact JSON** (`JsonValues`, in
  `tesseraql-yaml`, where Jackson is; core stays dependency-free), fractions as `BigDecimal`
  so `1.10` stays `1.10`; a scalar document is the scalar; a container an outbound call
  produced is re-wrapped. It never passes through `ResultRows.value` — the declaration runs
  after the read seam, on the published rows — so decision 7's allow-list is untouched.
- **A `format:` restatement is not a finding** (decision 17): the domain lint never classified
  `format:` at all, so nothing was added — what changed is that a domain referenced only from a
  `result:` entry is no longer "declared but never referenced" (`TQL-FIELD-4611`).
- **The schema's one `inputField` shape serves `input:`, `domains/` and `result:`**, so its
  `type` enum gains `json`; a separate `resultField` shape lists the four kinds; which surface
  honours which is the predicate's judgement, and `SchemaSyncTest` now compares the enum to the
  union.

### The guards, red before the fix

- `ResultDeclarationIntegrationTest` (runtime, PostgreSQL): a `jsonb` column and a text column
  holding JSON declared `type: json` are structures on JSON and a SQL NULL is `null`; a
  template navigates `row.payload.sku` and prints `row.payload_text` as
  `{&quot;sku&quot;:&quot;B-2&quot;}` (the `String.valueOf` half); `ordered_on` stored as
  `2026/01/15` and declared through a domain that alone carries `format: yyyy/MM/dd` is
  `2026-01-15`; `amount` stored as `1,234.50` is the number `1234.50`; `{not json` in row 1 is
  500 `TQL-SQL-2503` with details `column=payload, row=1, kind=json`; a declared column no row
  carries is 200 and one warning; a command step's `steps.read.rows` get the same.
- `DeclaredKindsTest`, `AppLinterDeclaredKindsTest` (the predicate on both surfaces, the chunk
  reader, the domain reference), `ResultDomainResolutionTest` (the compiled binding carries the
  domain's `format:` — a value only the domain has — on a source and on a step; a restated
  `format:` wins; an unknown domain fails the load), `SchemaSyncTest`.

Bracket (`work/temporal-semantics/t3/`): HEAD `35a17cc06` — 6 of the 7 integration rows red
(the JSON structure was the string `{"qty": 2, "sku": "A-1", "price": 1.10}`, the date the
string `2026/01/15`, the number `1,234.50`, the template 500 on `row.payload.sku`, the broken
row 200 with its text; the absent-column row is a non-regression row, green by nature).
`V-plain-map` (the wrapper without its `toString()`) — exactly the template row, the mapper
row green: the first trap the 2026-09-10 design recorded. `V-localdate` (the parsed
`LocalDate` returned as is) — every JSON row 500 `TQL-ROUTE-3001`, the template row green: the
second trap. `V-no-domain` (the loader not merging the domain into `result:`) — refused at
boot (`type: ''`), and `ResultDomainResolutionTest` 3/3 red. Fix — 7/7, and the yaml units.

### Filed, not fixed

- `locale:` on a read declaration (a European `1.234,50`): the pattern parses in the root
  locale; the export side has `locale:`, and unifying the vocabularies is decision 22's later
  slice.
- The constraint keys on a `result:` entry directly (`maxLength: 5` on a read) are accepted
  and not applied — documented (decision 18), not linted; a lint would be one more arm of
  `TQL-YAML-1064`.
- A `result:` on a job step's own `sql:` arm is an unknown key (`TQL-YAML-1043`, the step's
  creator does not read it); on a chunk reader or writer it is the lint error above and no boot
  refusal, since a job's chunk compiles at run time.
- A route's `result:` does not reach a `lookup:` that borrows its SQL, a Studio data browse, or
  the suite runner's own reads — the readers outside a route's pipeline.

## T3 follow-ups — the three filed items, designed 2026-09-14

**Status: complete — decisions 23-25 taken 2026-09-14, as recommended; F-A and F-B shipped.**
The
user chose the filed items as the next move after T3 shipped. Measured on `ffedc6e0b` by
reading.

### The measured premise

| # | item | what the code says |
|---|---|---|
| F1 | `locale:` on a read declaration | `DeclaredKinds.read` parses a `number`/`date`/`datetime` entry with `Locale.ROOT`; `InputField` has no `locale` key; `input:` parses in the request's negotiated locale (`InputBinder.bind(…, Locale)`), `export:`/`import:` in the block's `locale:` or `tesseraql.files.locale`. A column holding `1.234,50` has no way to say so. |
| F2 | constraint keys on a `result:` entry | `InputField.mergedWith` merges every domain key under a `result:` entry, and a route-local `maxLength: 5` on one is accepted and applied nowhere — the shape `refuseWriteKeysOnSources` refuses for `expect:` on a source. The merged model cannot tell a route-local key from an inherited one. |
| F3 | decision 22, `ColumnSpec` vs `InputField` | `ColumnSpec` has five keys — `name`, `label`, `column`, `type`, `format` — and 16 raise sites in six files; `InputField` has 22. The overlap is exactly `type` (date/datetime/number) and `format`, the same parsers (`ColumnValues.parse`). `label` and `column` are file-side and positional (a `columns:` list); nothing in `InputField` corresponds. `ImportSpec`/`ExportSpec` turn the list into `ColumnMapping`s through `toMapping()`; the loader never touches it. |
| F4 (new lead) | a job's `input:` with `domain:` | `ManifestLoader.loadJobs` parses jobs with no domain resolution and nothing else merges one into a `JobDefinition`; `FieldDomainRules` walks routes, consumers and tools only. A job input `{ domain: sku }` binds as an untyped string with the domain's keys applied nowhere and no finding — while the shared schema says a job's parameters "bind and validate exactly like a route's". Filed for [`audit-medium-leads.md`](audit-medium-leads.md), not this slice. |

### The decisions

23. **`locale:` is a key a `result:` entry and a domain may carry** — the language tag the
    entry's `format:` parses in (`de-DE` for `1.234,50`, `MMM` month names), judged by the
    strict parse `export.locale` is judged by (`TQL-YAML-1064` when the JDK cannot format it);
    the root locale when absent, as today. On `input:` it is not applied — a request parses in
    its own negotiated locale, and a form's user types in the UI's locale, not the column's —
    so a domain carrying it stays legal on an input (documented, the mirror of the constraint
    keys not applied on read), while `locale:` written directly on an `input:` entry is refused
    (`TQL-YAML-1064`), since it can never apply there. Alternatives: honour it on `input:` too
    (a field's text is always in its locale) — no, the storage locale is the column's, not the
    request's; default it from `tesseraql.files.locale` — no, that couples a read to the file
    defaults, and the platform default already reads `1,234.50`.
24. **A `result:` entry reads four keys — `type`, `format`, `locale`, `domain` — plus
    `description`, and is refused with anything else written on it** (`TQL-YAML-1064`, lint and
    boot). The mechanism that makes the refusal exact on both sides: the loader merges a domain
    into a `result:` entry by the read keys alone (`InputField.mergedForRead`), never
    `maxLength` and its kind, so a merged entry carrying a constraint key can only have been
    written with it — no domain lookup, no raw-tree walk, one predicate. Decision 18 stands
    (a domain's constraint keys are not applied on read); what changes is that writing one on
    the entry itself is no longer silent. Alternative: a warning — no; an accepted key that
    does nothing is the shape swept twice.
25. **Decision 22, measured: a file column gains `domain:`, and the records stay separate.**
    The only value unification would buy is declaring `type:` and `format:` once, and
    `domain:` buys exactly that: `columns: [ { name: ordered_on, domain: order_date } ]` on a
    route's `import:` or `export:` takes the domain's `type:` and `format:` (its own restating
    either), resolved by the manifest loader as `result:` is, judged by `ExportDeclarations`
    on the merged column as today, and counted as a reference by the domain lint. `label` and
    `column` stay file-side. A domain's `locale:` is not applied to a file column — a file has
    one locale, the block's — and is documented so. Folding `ColumnSpec` into `InputField` is
    rejected: two file-only keys would enter the input vocabulary as accepted-and-ignored, and
    a `columns:` list is positional where an `InputField` map is keyed. A job's columns are not
    resolved here — a job's own `input:` domains are not either (F4), and the job side is one
    fix, not two halves.

### The slices

- **F-A** (decisions 23, 24), shipped: `InputField.locale` (the 23rd key; not merged into an
  input by `mergedWith`, merged into a `result:` entry by `mergedForRead` with `type`, `format`
  and `description` alone), `DeclaredKinds` gains the locale judge (`localeProblem`, the export
  side's), the `locale:`-on-input arm and the strict read-key arm (`unreadKeys`, exact by
  construction of the merge); the read parses in the entry's locale. Guards, red on `ffedc6e0b`:
  a `de-DE` number through a domain on its own route (`/api/docs/de`) — on HEAD the domain
  cannot even carry `locale:` (`TQL-FIELD-4602`), so the whole suite is red there;
  `DeclaredKindsTest` (the input arm, the bad tag, the sixteen refused keys named, the German
  number and the German month name), `AppLinterDeclaredKindsTest` (written vs inherited
  `maxLength`, written vs inherited `locale`, `ja_JP`), `ResultDomainResolutionTest` (the
  domain's `maxLength`/`pattern` do not reach the entry, its `locale` does). Variant
  `V-mergedWith` (the full merge on a result entry) — four units red: the inherited key is
  reported as written, the resolution assertion fails. `work/temporal-semantics/t3/fa-*`.
- **F-B** (decision 25), shipped: `ColumnSpec.domain` (the sixth key) and `mergedWith` (type
  and format alone), resolved in `withFieldDomains` for a route's `import:`/`export:`
  (`RouteDefinition.withTransfers`, `ImportSpec`/`ExportSpec.withColumns`); schema
  `fileColumn.domain`; the domain lint counts it; `ExportDeclarations.columns` refuses a
  `domain:` on a job site (`TQL-YAML-1063`, lint and `job run`/registration through the one
  predicate). Guards, red on `0e4611d8b`: `TemporalExportIntegrationTest` — a `date` column
  typed through its domain alone writes `2026/01/15` (HEAD: the untyped `2026-01-15`);
  `FileTransferIntegrationTest` — two import columns typed through domains parse `2026/06/12`
  and `2.345,67` (HEAD: bound as strings, "column held_on is of type date but expression is of
  type character varying"); `ColumnDomainResolutionTest` (the compiled column carries the
  domain-only format, own format wins, unknown domain fails the load);
  `AppLinterColumnDomainTest` (the reference counts; a job's column refused).
  `work/temporal-semantics/t3/fb-*`.

---

## Scope out, each with its destination

- The write direction (a `LocalDateTime` bound into a `timestamp` column; MySQL's refusal of a
  DST-gap `TIMESTAMP` insert on a Los Angeles JVM) → the batch/binding line; T2 measures the
  read-then-rebind paths only.
- `connectionTimeZone` / session-zone configuration per datasource → the datasource line
  (decision 12 documents, does not add a key).
- Excel's own rendering of a date cell in the viewer's locale → the Excel codec line.
- `time` in the `type:` vocabulary (slice 5's "5a's own vocabulary question") → not taken up
  in T3, whose kinds are json/date/datetime/number; a `time` stored as text is filed with the
  `locale:` question above.

## The audit records, amended by this design

- [`audit-medium-leads.md`](audit-medium-leads.md): item 2's temporal half gains this record as
  destination; a new item 26 — the DuckDB/MySQL JSON 500 — enters "Defects surfaced that the
  audit does not carry".
- [`export-declarations.md`](export-declarations.md): "Today's temporal contract" is marked
  superseded by this record; its "Filed, not fixed" temporal bullet points here.
- [`export-hygiene.md`](export-hygiene.md) P5's filed note (the types half of the metadata seam
  beside `NamedRows`) — `JdbcValues` is that half, on the reader rather than on `NamedRows`.
