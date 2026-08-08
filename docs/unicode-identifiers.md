# Unicode identifiers — one verbatim name from DDL to template

> **Status: complete.** Design #625; slices #626 (contract + hard rejectors), #627
> (silent-skip extractors), #628 (the verbatim break + scaffold-demo regen), #629
> (sanitizers + CJK Studio search), #630 (WireNames/UnicodePaths HTTP layer, the
> 受注管理 gallery app, docs/identifiers.md) — all merged 2026-08-08. Track 5's IT
> settled open decision 1 the hard way: the router matches request paths as sent
> (encoded) against decoded templates, and its parameter names are Java regex group
> names (`[A-Za-z][A-Za-z0-9]*` — even `order_id` was unrepresentable),
> so the runtime now decodes non-ASCII triplets pre-match and carries positional
> stand-ins on the wire. Deviation from the letter of track 3: DocService's
> route↔table graph keys keep their lowercase fold — unquoted ASCII SQL is
> case-insensitive and the fold is a no-op for CJK; only the camel↔snake guessing
> died. Original design below.

Table and column names written in any script — Japanese is the
> driving case — become first-class identifiers, and the framework stops converting
> names between layers: the DDL column name **is** the YAML field name **is** the SQL
> bind name **is** the template model key **is** the JSON key. This is a one-shot
> pre-1.0 break under the latest-release-only support posture (AGENTS.md rule 10):
> the scaffolder's camelCase conversion layer is deleted, not aliased. Sibling of the
> contract-cleanup campaign (docs/contract-bugfixes.md, docs/vocabulary-cleanup
> slices): where those unified the framework's *own* vocabulary, this unifies the
> *user's* vocabulary.

## Why

Two problems share one root cause — the framework today has **two names for every
user concept** and a conversion layer between them.

1. **The conversion layer is busywork for ASCII users.** A column `order_date`
   becomes a field `orderDate`; every hand-written SQL statement is a manual
   camel↔snake mapping site (`insert into orders (order_date) values
   (/* orderDate */'…')`); `ViewFields.snake()`, `DocService.snake()` and
   `CrudScaffolder.Names.camel()` exist only to guess the mapping back. PostgreSQL
   folds unquoted identifiers to lower case, so snake_case is the only convention
   that survives every layer verbatim — camelCase never could.

2. **The conversion layer is *lossy* for Japanese.** `Names.camel()` lowercases,
   splits on `_`, and re-joins with an upper-cased first letter — all three steps are
   identity operations on kanji and kana except the join, so `顧客_名前` and
   `顧客名前` silently collide into the same field name. There is no correct
   camelCase form of a caseless script; the only coherent policy is no conversion.

Beyond the conversion layer, the identifier survey (2026-08-08) found that Unicode
names today fail in three distinct modes, of which the quiet ones are the dangerous
ones:

- **Hard rejections** (the safe kind): decision-table columns
  (`DecisionSets.identifier`), calendar source columns (`Calendars.requireIdentifier`),
  workflow `stamp:` columns (lint TQL-WORKFLOW-3111 **and** its runtime twin in
  `TransitionExecutor`), 2-way SQL scope aliases (`Sql2WayParser`), and the decision
  scaffolder all enforce `[A-Za-z_][A-Za-z0-9_]*` and error loudly.
- **Silent skips** (the dangerous kind): `RouteCompiler.pathParams` extracts
  `\{(\w+)\}` — Java's `\w` is ASCII — so `/{顧客ID}` is never bound and the query
  runs with a null bind and **no diagnostic**; the `AppLinter` scoped-table patterns
  (`SCOPED_TABLE_ALIASED`, `WRITE_TARGET`, the from-fallback) cannot see a Japanese
  table, so the **write-scope security lint stops firing** for exactly the tables it
  should guard; `MessageCatalog.interpolate` leaves `{顧客名}` as raw braces in
  user-visible error messages; `ViewEjector` link templates emit literal `{…}` into
  ejected pages; `OpenApiGenerator` drops non-ASCII path parameters from
  `openapi.json`; the template lints' `EACH_ALIAS`/`EXPR_ROOT` mismatch produces
  false "undeclared expression root" errors for `th:each="行 : ${rows}"`.
- **Destructive sanitizers**: `AppMigrations.historyTable` maps every non-ASCII
  character to `_`, so two Japanese-named apps **share one Flyway history table**;
  `DatasetSpool` keys collapse the same way; Studio's migration slug and the
  reference generator's heading anchors slug Japanese to the empty string;
  `DocService`'s search tokenizer splits on `[^a-z0-9]+`, making Japanese routes and
  tables unsearchable in Studio.

Meanwhile the *data plane is already Unicode-clean*: the 2-way SQL lexer and
expression parser use `Character.isJavaIdentifierPart` (Unicode-aware), row maps
carry column labels verbatim (`ResultRows`/`Labels` — and Japanese has no case, so
the Oracle fold is a no-op), plain-`ObjectMapper` JSON emits map keys verbatim, and
OGNL resolves `${row.注文日}` against those maps. The JSON schemas constrain **no**
name key. The gap is entirely in the regex-based edges and the scaffolder — which is
why this is fixable as a bounded campaign.

## The identifier contract

One definition, one authority class, used by every validator and extractor:

```
identifier     := [\p{L}_] [\p{L}\p{N}_]*
dotted pair    := identifier ( "." identifier )?     (where dotted forms are legal today)
```

A new `io.tesseraql.core.sql.SqlIdentifiers` (same "both paths ask here now, so
there is one answer to change" pattern as `ResultRows`/`Labels`) owns the pattern,
a `isIdentifier(String)` check, and the pre-compiled placeholder/bind variants
(`\{(name)\}` extraction, message placeholders). Call sites stop compiling their own
character classes.

**The regex remains the injection defense — quoting stays out.** The existing ASCII
patterns are documented in-code as the reason identifiers may land verbatim in SQL
text (`DecisionSets`: "Identifiers land verbatim in the generated statement";
`Calendars`: same). Widening to `\p{L}\p{N}_` **preserves** that property: Unicode
letters and digits cannot close a quote, open a comment, or terminate a statement.
Therefore identifiers stay unquoted everywhere, `DialectCapabilities.identifierQuote`
stays a declared-but-unread capability (recorded here as deliberate), and no quoting
machinery is added. The one exception is Postgres `NOTIFY`/`LISTEN` channel names,
whose sanitizer is *already* Unicode-permissive and feeds raw SQL — those get
double-quoted (track 4), which is the idiomatic Postgres form for channel names.

**Dialect support matrix** (unquoted Unicode identifiers): **all supported
dialects accept them.** PostgreSQL, MySQL, SQL Server, DuckDB and H2 allow Unicode
letters in unquoted identifiers, and Oracle's nonquoted-identifier rule admits
"alphanumeric characters from your database character set" — on an AL32UTF8
database (the modern default) Japanese names work unquoted, confirmed against a
real instance. Because CJK has no case, the engines' upper/lower-folding
differences are no-ops, and `Labels.normalize`'s Oracle heuristic
("all-uppercase label ⇒ driver folded it") is unchanged; its caseless edge (a
deliberately quoted all-caps-plus-kanji alias is indistinguishable from an
unquoted one) is accepted and documented — it predates this campaign. The
practical per-dialect constraint is the **identifier length limit, which several
engines count in bytes**: PostgreSQL truncates at 63 bytes (~21 kanji in UTF-8 —
the tightest), Oracle allows 128 bytes from 12.2 (30 bytes ≈ 10 kanji before),
MySQL 64 characters, SQL Server 128 characters. The docs page records this table;
no lint polices it — the database's own error is authoritative. The one genuine
prerequisite — a Unicode database character set on Oracle — is a deployment
concern, also documented rather than linted.

## The verbatim policy

The scaffolder and every reverse-mapping helper stop inventing second names:

- `CrudScaffolder.Names`: `field(column)` returns the column name verbatim (today:
  `camel(...)`), `pkField()` = `pkColumn()`, route-id prefix = table name verbatim,
  `htmlId(column)` = `"field-" + column` verbatim — the `_`→`-` rewrite is dropped
  (underscores are legal in HTML ids and CSS idents, and the rewrite was a second
  collision source). `camel()` is deleted. `label()`/`title()` remain as *display*
  fallbacks (humanization is a label concern; Japanese labels come from the message
  catalog, same as today).
- `ViewFields.snake()` fallback: deleted. The `row.get(name)` →
  `row.get(snake(name))` chain existed to bridge camel fields to snake columns;
  under the verbatim policy the first lookup is the only lookup.
- `DocService.snake()`: deleted; the table-docs domain-chip lookup keys on the
  verbatim name. Route↔table graph keys stop lowercasing.
- `DecisionScaffolder`: the "must be a lowerCamel identifier" gates widen to the
  shared contract; the rules table is `<name>_rules` with the name verbatim; its
  `snake()` helper is deleted.
- Scaffolded artifact names on disk (directories `web/<table>/`, SQL/rule/suite file
  stems, migration descriptions) keep the verbatim name, **NFC-normalized at write
  time**, and `ScaffoldChecksum` normalizes paths to NFC before hashing — otherwise
  macOS (NFD) and Linux (NFC) produce spurious drift for identical trees.

Consequence for ASCII apps: scaffolded field names change from `orderDate` to
`order_date` — the field name is now the column name. Gallery apps, scaffold-demo
(regenerated via `-Dtesseraql.scaffold.regenerate=true`, never hand-edited), suites,
and docs move in the same slice. This is the breaking half of the campaign and lands
as one slice so there is exactly one regen.

What deliberately **stays ASCII** (infrastructure names, not user data): app names
(`[a-z][a-z0-9-]{0,63}` — they become URL prefixes, directory names, and the history
table suffix), topic names, env profile names, preference keys, DuckDB
extension/secret names, Prometheus label names (spec-fixed), SCIM attributes
(RFC-fixed), error-code slugs.

## Tracks

**Track 1 — one contract, loud edges widen.** Introduce `SqlIdentifiers`; point the
hard rejectors at it: `DecisionSets.identifier` (all ~15 call sites),
`Calendars.requireIdentifier`, `AppLinter` TQL-WORKFLOW-3111 + `TransitionExecutor`'s
runtime twin (they must stay byte-identical in behavior), `Sql2WayParser` scope-alias
and file-placeholder names, `DecisionScaffolder` name/field gates, the DuckDB
`attach:`/`as:` lints. Existing "not a plain identifier" tests keep their teeth with
genuinely-invalid fixtures (`a-b`, `a b`, `a;b`) and gain Japanese-accepted cases.

**Track 2 — silent skips become sighted.** `RouteCompiler.pathParams` and
`OpenApiGenerator.PATH_PARAM` extract via the shared pattern;
the `AppLinter` scoped-table trio (`SCOPED_TABLE_ALIASED`, `WRITE_TARGET`, the
per-call from-fallback) widens so the write-scope guard sees Japanese tables;
`MessageCatalog.interpolate` and `ViewEjector.LINK_PLACEHOLDER` widen; the template
lints' `EXPR_ROOT`/`EACH_ALIAS` widen together (the false-positive pair);
`EmailFragments`/`MailComposer`/`EMAIL_FRAGMENT_REF` fragment names widen;
`JobExecutor`'s export-filename placeholder widens; the browser highlighter's
`isWord` in `tesseraql.js` matches the server-side (already Unicode-safe)
`SqlHighlighter`.

**Track 3 — the verbatim break.** Everything under "The verbatim policy" above, plus
gallery + scaffold-demo regeneration and suite updates, in one slice, one CHANGELOG
entry.

**Track 4 — sanitizers preserve, search finds.** `historyTable` keeps `\p{L}\p{N}`
(fixing the two-apps-one-history-table collision; `AppMigrations` and `AppMigrator`
copies change together — they are required to stay byte-identical); `DatasetSpool`
keys keep Unicode word characters; Studio's migration slug keeps `\p{L}\p{N}` runs;
`ReferenceGenerator` anchors keep Unicode letters and gain the missing
`Locale.ROOT`; `NOTIFY`/`LISTEN` channel names are double-quoted;
`DocService`'s search tokenizer indexes Unicode letter/digit runs and adds CJK
bigrams so Japanese routes, tables and titles are searchable in Studio.

**Track 5 — proof and documentation.** A Japanese gallery app (受注管理: orders +
order lines + a decision table + a workflow with stamped columns + Japanese i18n
messages + a suite) exercising scaffold → lint → route → suite end-to-end; ITs for
the percent-encoded URL round trip (browser encodes `/受注/{受注番号}` — verify the
route matcher decodes before binding), CSV/file export headers, `openapi.json`
parameters, and Studio pages over the Japanese app; a docs page stating the
identifier contract, the verbatim policy, and the dialect matrix (byte-counted
length limits, Oracle's Unicode-charset prerequisite).

## Open decisions

1. **Percent-decoding at the route matcher** is an assumption until track 5's IT
   proves it: `RedirectRenderer` encodes placeholder *values*, but generated path
   *templates* are emitted raw. If Camel's REST matcher compares encoded-vs-raw, the
   fix belongs in `restEndpoint`, and track 5 flushes it out.
2. **Route ids and `operationId`** become Unicode under the verbatim policy. The
   OpenAPI spec permits it; some client generators do not. Accepted and documented;
   revisit only if a marketplace consumer breaks.
3. **Mixed-script names** (`顧客Master`) work under the contract but reintroduce
   case-folding hazards this design does not try to referee (`DocService` map keys
   stop lowercasing in track 3, which resolves the known instance). The docs page
   recommends single-script names.
4. **Editor extension catch-up** (symbols/intel over Japanese identifiers) follows
   as its own ext release after the framework lands, per the established
   editor-catch-up pattern.

## Test plan

Per-track unit tests on `SqlIdentifiers` and each widened site; Japanese golden
files in the scaffolder tests; the existing rejection tests re-pointed at
still-invalid fixtures; the track-5 gallery app run under the full suite runner; the
gated dialect suites (PostgreSQL, MySQL, SQL Server, Oracle) over a Japanese-named
table to pin the support matrix — Oracle included, asserting unquoted Japanese DDL
and queries round-trip on an AL32UTF8 instance. NFC/NFD: a
checksum test feeding an NFD path asserts drift-free comparison.

## Sequencing

Five slices, one per track, in order — track 1 gives every later slice the shared
contract; track 3 is the visible break and regen; track 5 closes with proof. Each
slice is a PR against `main` with green CI, squash-merged.
