# Lookups and enrichment

Status: **designed 2026-08-11**. **Slices 1-3 complete** (composition, `enrich:` with a SQL reference,
and one outbound-call vocabulary); decisions 15-21 and slices 4-12 below.

A row holds a code; the name that code stands for lives somewhere else. That is the whole
subject. It looks like a small gap and it is not: the master may be in another database, the
code may be one of twenty kinds sharing one table, the name may exist in five languages, and
the same row set may be a JSON body, an HTML list, a spreadsheet, and a chunk step's reader.
Today an application answers this four different ways depending on which of those it is, and
one of the four — the most common one — is "denormalize it into the query or replicate the
master".

This document decides one mechanism for "fetch by key and fold into the rows", and one
mechanism for the case where the master is small enough that fetching by key is the wrong
question.

## What exists today

**A read route's data pipeline is a flat ordered sequence.** `sql:`, then `queries:` in
authored order, then `http:` sources; each result is published under its own key
(`RouteCompiler.buildDataPipeline`, the named-query and http loops). Nothing executes per row
anywhere on the read side.

**A named query can already reference an earlier result.** `NamedQueryBinder` resolves each
named query's `params:` against the live execution context, and says so in its javadoc — a
`params:` entry may read `sql.rows`. No test covers it, no page documents it, and no example
in the repository uses it. It is a wired capability, not a feature.

**Path resolution is plain dotted segments.** `EvaluationContext.resolve` walks map keys,
getters, and fields, with virtual `size`/`length`/`empty`. There is no indexing and no
projection: `sql.rows[0].id` and "the `id` of every row" are both unsayable. Whatever a param
binds from a result set, it binds whole.

**`nest:` composes, and only in JSON.** `JsonResponseRenderer.nest` groups a named query's
rows by one join key and attaches them as a list under each parent. `ResponseSpec.HtmlResponse`
has no `nest` field, so the same page rendered as HTML gets two unrelated model entries.
`NestSpec.on` is a map, but exactly one entry is legal (`TQL-YAML-1019`) and the renderer reads
only the first. The attachment is always a list, so a many-to-one lookup composed this way
yields a one-element array.

**A list view's columns are the result set's columns.** `columns:` selects, labels, links, and
sorts what the query returned; there are no expression columns. Anything a cell shows must be
a field of the row.

**SQL never spans datasources.** A route or a named query picks a datasource; a statement runs
on one connection. Composing result sets is the documented answer
([multi-datasource.md](multi-datasource.md)), and cross-datasource joins are explicitly out of
scope there.

**Field domains describe a field, not a value set with names.** A domain carries `type`,
constraints, `enum`, `widget`, `classification`/`mask`, and read-side `columns:`/`fields:` take
an explicit `domain:` reference. A domain cannot say "the legal values are the rows of that
table, and here is each one's label".

**Generated existence rules cover single-column foreign keys.** `scaffold crud` emits a
`<refEntity>Exists` rule per single-column FK ([validation-rule-sets.md](validation-rule-sets.md)).
A general code master keyed by `(type, code)` is not a foreign key of the referencing table at
all — the type is a constant, so no constraint can be declared — and nothing generates its
existence check.

**Batch SQL steps publish counts, not rows.** A `sql:` step publishes `affectedRows`;
`query-spool` publishes a spool reference and a count ([jobs.md](jobs.md)). `chunk:` is the only
per-row execution in the framework, and it is one level deep by construction.

**An export's model already mirrors a route's context.** Wave 2 of the export pipeline
published the extraction as `sql` and spooled every named result, so a template reads
`${sql.rows}` and `${header.rows}` the same way. `ExportModel` is built either `streaming`
(a single-pass iterator) or `repeatable` (an iterable), and asking for the wrong one fails
loudly (`TQL-LD-2856`).

**Phase 32's remaining half is this feature's cache.** The roadmap's caching phase still owes
"declared invalidation keys (a command declares which query caches it invalidates), and an
opt-in result cache with TTL. Tenancy-safe keys; correctness over hit rate."

## The three cases this has to answer

1. **A list of orders must show a partner name.** The order rows carry a partner code; the
   partner master is another system's table, reachable as a named datasource. Thousands to
   millions of rows; volatile.
2. **A detail page's `history` named query must show the same names.** The enrichment has to
   apply to a result set that is not the route's main one, without restating it.
3. **Twenty code kinds share one table.** `区分マスタ(区分種別, 区分コード, 区分名称)`, tens of
   rows per kind, nearly static. The names are needed on lists, on detail pages, in a form's
   `<select>`, in validation, in CSV and PDF output, and in five languages.

Cases 1 and 2 are the same mechanism. Case 3 is not, and treating it as the same mechanism is
the mistake this document exists to avoid: twenty `enrich:` blocks per route, restated on every
route, is the duplication the shared-definitions campaign spent itself removing.

## Decisions

### 1. `enrich:` is its own block, beside `queries:` and `nest:`

Three verbs, one distinction each:

| Block | Executes | Composes | Names |
| --- | --- | --- | --- |
| `queries:` | yes | no | publishes a result set under a name |
| `nest:` | no | yes | combines already-published results |
| `enrich:` | yes | yes | executes *keyed by the parent rows*, then folds in |

`enrich:` is not an option on `queries:`, for three reasons. A named query runs once with
request-derived params, unconditionally; an enrichment runs once per batch with row-derived
keys, and zero times when there are no keys — putting both under `params:` gives one key two
meanings. An enrichment's result is a join-side table, and publishing it under a name invites a
panel or a `source:` to point at a de-duplicated key set. And on a streaming export an
enrichment is not a stage in the pipeline at all: it is a transformation attached to a row
source, which the ordered-stage vocabulary of `queries:` cannot express.

The name is the Content Enricher of the integration literature, and of Camel — the substrate
this framework compiles to — and of Elasticsearch's ingest pipelines. The published page's
title and index carry *lookup*, which is the word an application author will search for.

Rejected: `lookup:`. It names the child side's mechanism, while the block's subject is what
happens to the parent rows; it also collides conceptually with `queries:` the moment someone
wants to look something up without merging it.

### 2. Enrichment merges into rows, and `nest:` gains `merge:`

An enrichment's output is columns on the parent row, not a nested document. That is what makes
it reach a list view's `columns:`, an export's `columns:`, and a hand-owned template's cells —
all of which see rows and nothing else.

`as:` (attach as an object or list) and `merge:` (add columns to the parent row) are one
vocabulary shared by `nest:` and `enrich:`, with identical meaning in both. `nest:` gains
`merge:` in the same slice, which also covers a case `enrich:` should never be used for: a
master small enough to fetch whole with an ordinary named query, joined in memory, with no
keyed execution at all.

`merge:` is many-to-one. More than one row per key is an error, not a silent first-wins.

### 3. Batching is the primitive; caching is second-order

An enrichment collects the distinct keys of its target rows and issues **one child execution per
batch**, not one per row. Memoizing per-row lookups by parameter — the intuitive fix — removes
only repeats: a hundred-row page with sixty distinct partners still costs sixty round trips.

Two execution strategies, defaulted by source:

| Source | Default | Round trips |
| --- | --- | --- |
| SQL | `batch` | `ceil(distinct keys / batchSize)` |
| HTTP | `perRow` | distinct keys |

HTTP defaults to `perRow` because a partner API is usually `GET /customers/{code}`; a source
that accepts a key list may declare `batch`. Request-scoped memoization applies to both, and is
what makes the same master used by two enrichments on one route cost one lookup.

Cross-request TTL caching is the same machinery Phase 32 owes and lands with it, not before —
see decision 12 for the key.

Rejected: per-row execution with a cache as the primary mechanism. It optimizes repetition and
leaves fan-out untouched, and it makes the round-trip count depend on the data rather than on
the declaration.

### 4. No row-value `IN`; the child SQL loops

A composite key does not render as `(a, b) IN ((…), (…))`. SQL Server does not accept a row
constructor there, and the five supported dialects (`Dialect`) must all run the same file — a
2-way SQL file stays runnable in a plain SQL tool, which is the constraint that rules out
per-dialect rendering.

The framework binds the distinct key set as `keys`; the file states the shape:

```sql
-- single key: the existing IN-list bind
select 顧客コード, 顧客名 from 顧客 where 顧客コード in /* keys */('C001')
```

```sql
-- composite key: one disjunct per key, portable everywhere
select 発注者コード, 受注者コード, 取引先名
from   取引先
where
/*%for k : keys separator ' or ' */
  (発注者コード = /* k.発注者コード */'BUY001' and 受注者コード = /* k.受注者コード */'SUP001')
/*%end*/
```

`keys` is a list of scalars for a single key and a list of row maps for a composite one. This is
the same `%for` an author already writes for a multi-row insert, and the dummy values keep the
file executable.

### 5. `batchSize:` and `maxKeys:` are different numbers, and oversize splits

An oversized key set is **divided and executed in several statements**, not refused. Splitting
is not an optimization here; it is required by the dialects:

| Dialect | Limit that bites | Consequence |
| --- | --- | --- |
| Oracle | 1000 expressions in an `IN` list | the single-key sugar fails at 1000 |
| SQL Server | 2100 parameters per statement | a two-column composite fails near 1050 |
| PostgreSQL | 65535 bind parameters | effectively unreachable |

So `batchSize:` is how many keys ride one statement, defaulted by the framework from the
dialect and the key arity — an author does not have to know Oracle's number. `maxKeys:` is a
separate ceiling on the total distinct key count, and it stays: without it a five-million-row
export enriches by silently issuing ten thousand round trips, which is the failure this
codebase has spent a campaign removing. Exceeding `maxKeys:` fails and names the enrichment,
the distinct count, and the ceiling.

Batches are merged by key, so their return order is irrelevant — unlike an export's grouping,
this carries no ordering contract. A key set of zero executes nothing.

Deferred to a later slice: padding the final batch to `batchSize` so every statement has one
shape, which bounds plan-cache entries on Oracle and SQL Server. It is safe — the predicate is
a disjunction, so a duplicated key returns no duplicate row — but it interacts with
`BoundSql.variant` and the coverage trace, and those should be looked at first.

### 6. A partial merge is never left behind

When one batch of seven fails, the enrichment fails. With `onError: empty` it degrades, and
degrading means **no key is merged**, not the three batches that happened to succeed. A list
where some rows carry a name and some do not is the worst artifact this feature could produce:
it reads as a data problem and gets reported as one.

Degrading is logged and metered, exactly as an `http:` source's degradation is.

### 7. `on:` takes composite keys everywhere it appears

`nest:` accepts one pair today by lint. `enrich:` needs several, and one key must not mean two
things in two blocks, so `nest:` gains composite keys in the same slice. Join keys compare by
the canonical normalization `nest:` already applies (INTEGER 1 matches BIGINT 1); a composite
key normalizes element-wise into a tuple. That normalization becomes shared code with three
callers — `nest:`, `enrich:`, and catalogs — which is what justifies lifting it out of the JSON
renderer.

`source:` is not used for any sub-key. The vocabulary cleanup freed that word for "where the
rows come from" and a view's `source:` already spends it; an enrichment names its target with
`into:` and a catalog reference with `from:`.

### 8. A code catalog is a context feature, not a view feature

The twenty-code-master case is answered by loading each small master whole, caching it
application-wide, and resolving labels at render time. The resolution must live **below** the
view layer or the ladder breaks: a view ejected to a hand-owned template would silently lose
its names, and ejection is supposed to freeze the layout, not remove behavior.

So a catalog is published into the execution context under the reserved name `codes`, with one
uniform call shape for any key arity:

```html
<td th:text="${codes.取引区分.of(row.取引区分)}">現金</td>
```

A declarative view's `columns: [{ name: 取引区分, domain: 取引区分 }]` is sugar that expands to
exactly that call, so ejection emits it verbatim. Export and mail templates read the same
context. `codes` joins `v` and `views` as a reserved model name (`TQL-VIEW-3319`'s family), and
it is exposed to templates as a plain map-like object — jxls's JEXL does not read record
accessors, which the export campaign already learned the hard way.

Two access modes over one catalog:

- **Resolve** — render-time, does not touch the rows. Templates, `<select>` options, HTML.
- **Merge** — adds a column to the rows. JSON bodies, CSV and workbook columns. This is
  `enrich:` with `from:` naming a catalog instead of `sql:`/`http:` — the catalog is the third
  source of the same merge engine, not a second engine.

JSON never substitutes silently: a response body is a contract, so a code stays a code unless
the route asks for the label column by name. HTML substitutes by default, which matches
presentation hints being excluded from OpenAPI emission.

### 9. `catalogs:` and `domains.codes:` are separate declarations

A catalog keyed by `(会社コード, 商品コード)` is a property of a table, not of a field, and
attaching it to a field domain is a category error — the field holds one column of the key.

| Declaration | Key | Gives |
| --- | --- | --- |
| `catalogs:` | single or composite | render-time resolution, `merge:` |
| `domains.codes:` (references a catalog) | single only | the above, plus `<select>` options and value validation |

This keeps the common case a single line in the domain, and it explains rather than hides why a
composite catalog does not produce form options: **dependent selects are a separate feature**
(see Out of scope).

### 10. Catalog or enrichment is decided by size, not by key arity

| | Code master | Business master |
| --- | --- | --- |
| Rows | tens to hundreds | thousands to millions |
| Change | nearly static | daily or continuous |
| Fits in memory whole | yes | no |
| Mechanism | catalog | `enrich:` |
| Queries | one per refresh | `ceil(distinct / batchSize)` per request |

A composite-keyed master of three hundred rows is a catalog. A single-keyed master of five
hundred thousand is an enrichment. The language dimension multiplies a catalog's size and does
not change the test.

### 11. Validation resolves against the key set, and a miss re-checks the source

A catalog-backed domain validates that the value is in the catalog — the check nothing
generates today, because a general code master is not a foreign key (see What exists today).
Three rules make it safe:

**The key set is language-independent.** A catalog holds a distinct key set and, separately, a
`(language, key) → label` map. A code whose Japanese label is missing still exists. Validating
against per-language maps would reject valid codes for missing translations.

**A cache miss is not a rejection.** A hit passes. A miss issues one real query against the
source before rejecting, and refreshes the entry. Cost lands only on the rejection path, and a
code added a minute ago is never wrongly refused during the TTL window.

**Labels see every row; options and validation see the active subset.** A retired code must
still render on last year's orders, must not be offered on a form, and must not pass
validation. `active:` marks the subset. Where activity is a date range it is evaluated at
resolution time rather than filtered in the loading SQL, so a cache that outlives midnight does
not keep yesterday's answer.

The violation reuses the `enum` violation's field-error shape. A catalog is a dynamic `enum`;
clients and suites should not have to learn a second one.

### 12. Language is a dimension of the catalog, not part of the key

The requested language is ambient request state, not a column of the row being rendered. So the
call site is unchanged in every language — `codes.取引区分.of(row.取引区分)` — and the catalog
holds a label map per language.

Locale resolution and the fallback chain are the ones i18n already uses. Nothing new is
invented, and a missing translation falls back to the default language rather than to the raw
code. Missing translations are reported once per load on the operations surface, not once per
request in the log.

**Where the ambient locale comes from differs per surface, and each must be stated:**

| Surface | Locale |
| --- | --- |
| HTTP route (HTML/JSON) | the request's resolved locale |
| Export | the export's declared `locale:` — not a request's |
| Batch job | declared by the job; there is no request |
| Mail | the recipient's language |

A surface with no declared locale must not fall through to the JVM default. "The report came
out in English because the server's locale was" is the characteristic failure of this feature
and is prevented by refusing the undeclared case at build time.

Ordering of `<select>` options comes from an `order:` column, not from sorting labels: label
collation is locale-dependent, and business code masters already carry a display-order column.

Labels may also come from the message catalog instead of a table
(`label: { message: "code.取引区分.{key}" }`), which puts them in the translation workflow the
Studio message editor already serves, and adds no per-language table.

### 13. Invalidation is keyed by source table, not by catalog name

When twenty code kinds share one table, the maintenance command upserts a row whose kind is
request data. It cannot declare `invalidates: [codes.取引区分]`, because which catalog is
affected is not known until the row is written.

It does not need to. A catalog is chosen for being small enough to hold whole, which is exactly
the condition under which over-invalidation is free: reloading all twenty derived catalogs
costs twenty small queries. So the unit is the table.

```yaml
# web/admin/codes/post.yml
steps:
  upsert: { file: upsert-code.sql }
invalidates: [区分マスタ]
```

Resolving a table to its catalogs must not require parsing SQL — the SQL-to-table dependency
graph is deferred work ([documentation-portal.md](documentation-portal.md)) and this feature
must not wait on it. So a catalog declares its source declaratively:

```yaml
# domains/codes.yml
domains:
  取引区分:
    type: string
    maxLength: 2
    codes:
      table:    区分マスタ
      where:    { 区分種別: '01' }
      key:      区分コード
      label:    区分名称
      language: 言語コード
      order:    表示順
      active:   有効フラグ
```

`file:` remains for shapes a `table:`/`where:` pair cannot express — a join across a code table
and its per-language names — and then the declaration lists the tables it reads. Because the
scaffolder knows the table it generates a maintenance screen for, it emits `invalidates:`
itself.

A per-table version stamp also means the staleness check is one row regardless of how many
catalogs derive from it.

**`invalidates:` is an optimization, not the guarantee.** A master written by another system
bumps nothing. Underneath it sit the TTL, the manual refresh, and decision 11's miss-rechecks —
so a stale catalog is a display delay, never a wrong rejection.

Rejected: expression-valued invalidation (`invalidates: ["codes.{body.区分種別}"]`). It makes
the author maintain a mapping from code-type values to catalog names, to save queries that cost
microseconds on data selected for being tiny.

### 14. Refresh is an atomic swap that survives its own failure

Propagation follows the precedent the framework already sets for live-editable state: feature
flags are re-read by checking a cheap stamp — file mtime and size — and swapping an immutable
value in (`FlagsSpec`). No broker, no invalidation messages. The database-backed analogue is a
version row per source table; each instance reads the stamp on an interval and reloads what
changed. `queue-consume` is not the mechanism: it is a competing consumer, and one instance
would take the message.

Four properties, each of which is a defect if omitted:

1. **Load fully, then swap the reference.** Never clear and refill — a failure mid-refresh
   would empty every name on every screen and fail every validation.
2. **A failed refresh keeps the previous data** and surfaces itself: metric, log, and an
   operations row carrying last-success time and last error.
3. **One in-flight reload per catalog**, so twenty catalogs across N instances do not stampede
   a maintenance save.
4. **Refresh is a governed route** with `security:` and a policy, audited like any other write.
   The instance that bumps the stamp reloads synchronously so the operator who pressed the
   button sees the effect immediately; other instances follow within the check interval. That
   asymmetry — *immediate for you, seconds for everyone* — is documented, or it gets reported
   as a bug.

Per-tenant catalogs key the cache and the stamp by tenant, so one tenant's refresh neither
serves another's data nor invalidates it.

### 15. One outbound call, one vocabulary

`method`, `url`, `headers`, `query`, `body`, `credential`, `expectStatus`, `connectTimeout`
and `requestTimeout` mean the same thing wherever a call is declared — a job's `httpCall:`
step, a route's `http:` source, an enrichment's `http:` reference. They are one record, not
two overlapping ones, and `HttpSourceSpec`'s restatement of nine `HttpCallSpec` fields ends.

`select:` (which part of the response becomes rows) and `onError:` (`fail` | `empty`) are the
read side's additions, and they appear wherever a response becomes rows.

The structure this settles is a 2×2 rather than a list of features:

| | publishes a named result | folds into rows |
| --- | --- | --- |
| SQL | `queries:` | `enrich:` with `sql:` |
| HTTP | `http:` | `enrich:` with `http:` |

### 16. Read-only is defined by effect, not by HTTP method

`http:` sources were GET-only and body-less, as the proxy for "a read route performs no
write". The proxy is both too strict and too weak. Too strict: JSON-RPC, GraphQL, and every
`POST /partners/search {"codes": […]}` batch-lookup endpoint are refused — which is to say,
precisely the references worth batching. Too weak: nothing stops a partner's `GET` from
mutating, so the guarantee was never the method's to give.

What actually holds the line stays exactly where it was: `http:` and `enrich:` are unavailable
on command routes (`TQL-YAML-1022`), so no outbound call is ever made inside the framework's
own write transaction. On top of that:

- **A non-GET call is declared, never inferred.** `method: POST` is written out; the presence
  of `body:` does not silently change the method.
- **`body:` on a method that carries none is a build error**, not a body dropped on the floor
  — today the client documents it as "ignored", which is the silent tolerance this codebase
  has spent a campaign removing.

This is a breaking change to a published contract (`docs/connectors.md` states "Always GET,
never a body"), taken deliberately before 1.0 under mandatory rule 10.

### 17. Batching is one primitive across SQL and HTTP

Wave 1's slice 3 was designed as "HTTP is per-row, and the cache carries it". That was a
conclusion drawn from the GET-only constraint rather than from the problem, and decision 16
removes the constraint. A reference that accepts a key list should get the same one-round-trip
property SQL has:

- **`mode: batch`** sends the distinct key set in one request — `body: keys`, the same
  `keys` bind the SQL reference receives — and `batchSize`/`maxKeys` mean what they mean for
  SQL.
- **`mode: perRow`** issues one request per distinct key, for the `GET /partners/{code}`
  shape that cannot take a list. It stays the default for HTTP, because that shape is the
  common one; it is no longer the only option.

A **batch** response must carry the key columns in its rows — `on:`'s reference side matches
them exactly as it matches a SQL reference's. A **perRow** response needs no key: the answer
belongs to the key that was asked for, so the match is implicit.

### 18. A body envelope is a separate decision, not a smuggled one

`body:` resolves a single context path and serializes it
(`HttpCallClient.bodyPublisher`), so `body: keys` sends `["P1","P2"]` and nothing more
elaborate. JSON-RPC's `{"jsonrpc": "2.0", "method": …, "params": {…}, "id": …}` and GraphQL's
`{"query": …, "variables": {…}}` need a body *template*, which is a new authoring surface with
its own questions (interpolation syntax, escaping, where the response's `id` correlation
lives). Decisions 15-17 deliberately stop short of it: they are worth having without it, and
it is worth deciding on its own evidence.

Until then, an envelope-shaped API is reachable the way it always was — a job step or a
service — and a plain `POST … {"codes": […]}` endpoint is reachable directly.

### 19. A command fetches before it opens its transaction

The rule was "`http:` is unavailable on command routes". The invariant behind it is narrower,
and the export pipeline already states it (decision 2 there): a network call inside the window
where a connection, a transaction and a cursor are held pins all three for however long the
partner takes, so an export runs its `http:` sources *before* the extraction.

A command has the same window and takes the same fix. `http:` becomes legal on the
transactional recipes, executed **before the connection is acquired** — the steps bind the
fetched value like any other context entry (`partner.body.name`), and the transaction never
waits on a third party. It answers the requirement the current rule refuses outright: *fetch a
name over HTTP and write it, as of this transaction*, without trusting the caller to supply it.

- **Fail-closed.** A failed fetch fails the command before a row is written. `onError: empty`
  remains available and means the author declared the value optional.
- **No retry.** The call is made once; the layer that makes a retried *request* safe is the
  command's own `idempotency:`, which already exists.
- **A rollback does not un-call.** This is the residue no design removes: the write can roll
  back, the request cannot. With a non-GET method now legal (decision 16), the author states
  that the call is a reference with `readOnly: true`. The framework guarantees the declaration
  exists, not that it is true — anything with a side effect belongs after the commit, in the
  outbox.
- **What is fetched is a snapshot** taken shortly before the commit. For "store the name as of
  this transaction" that is the requirement, not a defect.
- **Timeouts matter more here than on a page**: the caller waits, and the write queues behind
  the call. The per-source `connectTimeout`/`requestTimeout` and the circuit breaker apply, and
  the guide says to declare them short.

`enrich:` stays unavailable on commands, unchanged: there are no rows to fold into before the
write. The value lands in the context and a step binds it.

### 20. Webhook delivery rides the one outbound gateway

`WebhookNotifier` builds its own `HttpClient`: no allow-list, no named credentials, a
hard-coded ten-second connect timeout, no request timeout, and no share in the per-host circuit
breaker. The framework's stated egress posture — deny by default — therefore has a hole exactly
where the framework itself calls out, and decisions 15-16 leave it as the one remaining path
with its own rules.

Delivery moves onto the gateway every other call uses. The HMAC signing, the payload shape, the
retry and dead-letter policy and the operations surface are untouched; only the transport
changes.

Breaking: a webhook whose host is not in `allowedHosts` stops being delivered until the host is
listed. That is a one-line configuration migration, and it is the correction of a claim the
documentation already makes.

**A note that belongs with it.** A webhook delivery reads its response only to decide success
or failure (`TQL-BATCH-5303`); nothing flows back into the application. A command that needs
the partner's answer *stored* writes a pending row and lets a job's `httpCall:` step complete
it. That is the saga the framework supports — atomicity and a synchronous external answer
cannot both be had — and the guide should say so rather than leave it to be discovered.

### 21. A per-row reference keys its URL, and the key is encoded

`GET /partners/{code}` is the ordinary shape of a reference API, and nothing in the framework
could express it: every other call is made once, with a URL known at build time. A `perRow`
enrichment's url therefore takes `{key.<column>}` placeholders, filled per key before the call
is handed to the gateway — so the client keeps receiving a finished URL and the host faces the
same allow-list check it always did.

Values are **percent-encoded for a path segment**, not with `URLEncoder`, which encodes for a
query string: it renders a space as `+` and passes `/` through untouched. A key carrying either
would otherwise address a different resource than the one asked for. Everything outside RFC
3986's unreserved set is escaped.

A `batch` reference needs none of this — its keys ride the body — and a `perRow` reference that
mentions neither `{key.…}` nor `key.` in its query, body or headers sends the identical request
for every key, which is `TQL-YAML-1048`'s HTTP twin.

Out of scope, still: a body *template*. Decision 18 stands — keying a url is substitution into
a string the author already wrote, while an envelope is a new authoring surface.

## Waves and slices

**Wave 1 — composition and enrichment**

1. **`nest:` gains `merge:` and composite `on:`.** No execution, no batching, no cache: the
   in-memory half only, plus lifting key normalization out of `JsonResponseRenderer` into
   shared code. Settles `merge:`'s semantics (many-to-one, collision is an error) before
   anything depends on them, and already answers "the master is small, fetch it whole".
2. **`enrich:` on read routes, SQL source, batched.** Key extraction, distinct, `batchSize`
   splitting, `maxKeys`, tuple matching, `merge:`/`as:`, request-scoped memoization,
   `into:` targeting `sql` or a named query.

   Slice 1 settled a boundary this depends on: `nest:` composes the *response body*, so
   `into:` names a body key and JSON is the only surface that has one. `enrich:` composes
   *result sets* — `sql` or a named query, in the execution context — which is why it reaches
   an HTML list's `columns:` and an export's, and why it is a second block rather than a
   fifth key on `nest:`.
3. **One outbound-call vocabulary** (decisions 15-16). `HttpSourceSpec` stops restating
   `HttpCallSpec`'s fields; `http:` sources gain `method:` and `body:` and lose the GET-only
   restriction; `body:` on a bodyless method becomes a build error. Breaking, and no new
   feature of its own — the vocabulary has to be one before an enrichment can borrow it.
4. **`enrich:` with an `http:` reference** (decisions 17, 21). `mode: perRow` (default) and
   `mode: batch` over the same key set, the same `batchSize`/`maxKeys`, the existing gateway's
   egress allow-list, credentials and degradation metric, plus `onError:` under decision 6's
   all-or-nothing rule.

5. **A command fetches before its transaction** (decision 19). `http:` becomes legal on the
   transactional recipes, ordered before the connection is acquired; `readOnly:` and its lint;
   the guide's timeout advice and the rollback-does-not-un-call sentence.
6. **Webhook delivery on the gateway** (decision 20). The allow-list, named credentials,
   configured timeouts and the shared circuit breaker; the migration note; the saga documented
   where a reader looks for it.

**Wave 2 — catalogs**

7. **`catalogs:` and `domains.codes:` (single language).** Whole-table load, the `codes` context
   object, `of(...)`, render-time resolution in declarative views and in ejected templates,
   `active:`, `order:`, `<select>` options, validation with miss-rechecks.
8. **Multi-language.** The language dimension, i18n's locale resolution and fallback, the
   per-surface locale table of decision 12 and the build-time refusal of an undeclared one,
   message-sourced labels.
9. **Invalidation and refresh.** Per-table version stamps, `invalidates:` on commands, the
   atomic swap and its failure behavior, the operations surface, the scaffolder emitting
   `invalidates:` for generated maintenance screens.

**Wave 3 — the remaining surfaces**

10. **Export.** Repeatable models first (one pass for keys, then batches); then streaming, where
   the enrichment becomes a sliding window over `batchSize` rows and the request-scoped cache
   stops being an optimization and starts being load-bearing.
11. **`chunk:`.** Enrichment between reader and writer, merged columns visible to the writer,
   with the window/skip interaction spelled out: a lookup failure is a window-level failure and
   must not be recorded as one row's skip.
12. **Editor and Studio catch-up.** Symbols for `catalogs:`/`enrich:`, completion for `domain:`
   and `from:`, a catalogs page listing rows, last load, last error, and refresh.

## Out of scope (documented, not implied)

- **Sorting, searching, or paginating by an enriched or resolved column.** Both mechanisms
  compose after the query; only SQL can order. A screen that must sort by partner name still
  projects the master and joins — the recommendation of
  [multi-datasource.md](multi-datasource.md) is unchanged by this document, and that is not a
  gap this feature is failing to close.
- **`enrich:` on a command's `steps:`.** It would hold a write transaction open across a call
  to another system, against the single-connection stance. A command that needs another
  system's value fetches it before the transaction or reads a projection.
- **Parallel batch execution.** Sequential on one connection; an export's named results already
  run on the extraction's connection by construction.
- **Dependent (cascading) selects.** A composite-keyed catalog cannot offer options for one
  field without the other field's value. That is a form feature, not a catalog feature.
- **Catalog access from SQL.** Catalogs live in the runtime; `order by 名称` is not reachable
  and is not meant to be.
- **Nesting more than one level.** Unchanged from the export pipeline's wave 2: an order, its
  lines, and their shipments still has no expression.
- **A second cache implementation.** The cross-request TTL cache is Phase 32's, with Phase 32's
  tenancy-safe keys; this document contributes the requirement, not a parallel mechanism.

## Error codes

Proposed; exact numbers are reserved against `ErrorIndex` when each slice lands.

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-YAML-1045` | error | `enrich:` `into:` names neither `sql` nor a declared named query |
| `TQL-YAML-1046` | error | `enrich:` declares none of `sql:`, `http:`, `from:`, or more than one |
| `TQL-YAML-1047` | error | `key:` and `on:` disagree in arity |
| `TQL-YAML-1048` | error | `merge:` names a column the target rows already carry |
| `TQL-YAML-1049` | error | `from:` names an undeclared catalog |
| `TQL-FIELD-4614` | error | `domains.codes:` references a composite-key catalog |
| `TQL-FIELD-4615` | error | a catalog declares `language:` without `label:` |
| `TQL-SEC-4142` | error | a cached child query reads `/*%scope … */` or ambient `principal.*` without those in the cache key |
| `TQL-SQL-2114` | runtime | an enrichment exceeded `maxKeys:` |
| `TQL-CAMEL-3113` | runtime | `merge:` found more than one row for a key (**taken, slice 1**) |
| `TQL-APP-4206` | runtime | a catalog refresh failed; the previous data is still serving |

## Risks

**The cache key is the security-sensitive part.** A child query carrying a `/*%scope … */`
predicate or an ambient `principal.*` bind, cached under a key of only the business code, leaks
across tenants and roles. The key is the composition of datasource, source path,
`BoundSql.variant`, every effective bind, and the tenant; the alternative is refusing to cache
such a query. `TQL-SEC-4142` exists so the choice is made at build time rather than discovered.

**Enrichment can hide an N+1 rather than remove one.** Round trips per request must be visible
— batch count and distinct key count on the trace and in metrics — or a page that quietly makes
forty round trips looks exactly like one that makes one.

**Read-side param chaining stays undecided until slice 2.** `NamedQueryBinder`'s ability to bind
`sql.rows` is untested and undocumented today. Once `enrich:` exists it is the wrong way to do
the same thing, and the honest options are to document named queries as mutually independent
and refuse row-derived params, or to keep the capability and cover it. Slice 2 picks one; leaving
it in its current state is not an option.

## Where to go next

- [response-shaping.md](response-shaping.md) — `nest:` and the response vocabulary this extends
- [field-domains.md](field-domains.md) — the domain declaration `codes:` joins
- [multi-datasource.md](multi-datasource.md) — composing result sets, and the projection
  pattern that remains the answer for sortable columns
- [export-pipeline.md](export-pipeline.md) — the model shape wave 3 enriches
- [jobs.md](jobs.md) — the `chunk:` step wave 3 reaches
