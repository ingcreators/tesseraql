# A read declares how long its rows are held, a write declares what it made stale: the result hold, its keys and its invalidation

> **Status: designed 2026-09-20. No slice started; the user names each one.** Phase 32 of
> `docs/roadmap.md` owes "declared invalidation keys (a command declares which query caches it
> invalidates), and an opt-in result cache with TTL. Tenancy-safe keys; correctness over hit
> rate." The first half — `Cache-Control`, a strong `ETag`, `304`, htmx-aware — shipped in
> 0.6.0 (#360, #637). This record measures what stands and decides the second half in three
> slices: **S1** the hold on a route's sources and the reach of `invalidates:`; **S2** the
> enrichment memo (`docs/audit-low-leads.md` F122, code M) and the enrichment's hold; **S3**
> the writers that are not command routes.

A query route can tell a browser how long to keep its response and answer a revalidation with
`304`. It cannot tell the runtime to keep the rows: every request renders from a statement
executed for it, and the `304` is computed after the render. The catalog store already holds a
whole table and drops it when a command says `invalidates: [table]`; the roadmap's remaining
half is the same shape for an ordinary statement — held for a declared time, keyed so no
principal, tenant or page reads another's rows, dropped by the write that changed them.

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by this record.
Site-excluded, so the prose lint does not read it; `sync-content.mjs` and `InternalDocsSyncTest`
do.

## What was measured

Measured 2026-09-20 on main `56e3e5613` (0.19.0-SNAPSHOT). Rows 1-3 are **RUNs**: row 1 the CLI
of the installed jars of this tree (`mvn install -DskipTests`), rows 2-3 a scratch
`CachingProbeIntegrationTest` in `tesseraql-runtime` (Testcontainers PostgreSQL 16 started with
`log_statement=all`, statements counted in the container log; the class lives in the session
scratchpad, not in the tree). Rows 4-16 are readings of the code and the records.

| # | Run or reading | Result |
| --- | --- | --- |
| 1 | RUN — `tesseraql lint` on a six-route fixture: `cache:` on a `command-json`; `cache.visibility: public` on `auth: browser`; `invalidates:` on a `query-json`; `invalidates: [orders]` on a command of an app with no catalogs; a `cache:` block written under `sources.main.sql`, beside the arm under `sources.main`, and under `sources.main.enrich.partner` | `TQL-YAML-1025` ×2 (error), `TQL-FIELD-4620` error ("there is no commit to invalidate catalogs after") and warning ("which no catalog reads … the app declares no catalogs"), and **`TQL-YAML-1043` warning ×3 — "silently ignored"** for every spelling of a source-level `cache:`. The predicates of both shipped declarations stand; the declaration this record adds has no home yet and lint says so. |
| 2 | RUN — a `query-json` route with `cache: {maxAge: 30s, visibility: public}` over `orders.sql`: GET, GET with `If-None-Match`, GET | `200` (`Cache-Control: public, max-age=30`, `ETag: "c2bd…"`), `304` in 7 ms with an empty body and the same tag, `200`. **Three statements logged for three requests: the `304` executed the query.** A fourth GET with `HX-Request: true` answered the same tag and no `Vary` — a JSON route has no shell to negotiate. |
| 3 | RUN — the F122 fixture (`main` and `history` each carry an `enrich:` on `partners.sql`), requested twice | `partners.sql` logged **four** times: two per request, one per `enrich:` block, the second request no cheaper than the first (15 ms then 6 ms, both with every statement). `order.sql` and `history.sql` twice each. |
| 4 | What `cache:` is (`CacheSpec`, `HttpCacheProcessor`, `HttpCacheRules`) | `maxAge`/`visibility`/`etag`/`staleWhileRevalidate`; the processor runs after the renderer (`RouteCompiler:542` for JSON, `:1957` for HTML), stamps `Cache-Control` on `200` only, hashes the body with SHA-256 into a strong tag and answers an **exactly equal** `If-None-Match` (`tag.equals(ifNoneMatch)` — no list, no `*`, no `W/`) with `304`; streaming bodies are not hashed. `TQL-YAML-1025`: query recipes only, `public` only on `auth: public`, durations must parse. `HtmlResponseRenderer:316-323` adds `Vary: HX-Request` under `shell: auto` (#637). Stateless by design, as the 0.6.0 entry says: "a 304 saves transfer, not compute" — row 2 is that sentence measured. |
| 5 | Census of the two shipped declarations in the tree | `^cache:` and `^invalidates:` occur in **no** route of `examples/*/web` or of the bundled system apps (`tesseraql-*/src/main/resources`). The scaffolder emits `invalidates:` for a table a catalog reads (`CrudScaffolder:85-88`); nothing in the gallery declares a catalog and a command over the same table. Neither declaration has a dogfood. |
| 6 | `invalidates:` as built (#732, `docs/lookups.md` decision 13) | `RouteDefinition.invalidates` (string or list); `CatalogInvalidateProcessor` mounted after the command and beside `TopicEmitProcessor` on routes (`RouteCompiler:902`) and MCP tools (`:2241`), so a rollback bypasses both; `CatalogStore.invalidate(tables)`; lint `TQL-FIELD-4620` (`DocumentRules.lintInvalidates`): error on a recipe other than `command-json`, warning for a table in no catalog's `sourceTables()` (`LintContext.catalogTables()`). |
| 7 | The stamp (`JdbcCatalogStore`, `db/migration/catalog/V1__catalog_version.sql` ×3 vendors) | `tql_catalog_version(table_name pk, version, updated_at)` on the **main** connector, created by `ensureSchema` at boot (a failure disables the stamp, never the catalogs, `:328-346`). `invalidate` drops the local holds whose tables intersect and `bumpStamps` runs `update … version = version + 1` per table, inserting the row on a miss — **only for tables a catalog reads** (`stampedTables`, `:231-257`); a failure is a WARNING, never the command's. Readers call `refreshStamps` at most once per `STAMP_INTERVAL_MILLIS = 5_000` (`:121`), one `select table_name, version` for every catalog at once; a failed read falls back to the TTL alone (`:293-325`). A hold is stale when its TTL elapsed **or** `stampOf(spec) > stampAtLoad` (`:436-448`); loads are single-flight per catalog (`loadLocks`, `:377-386`); a never-loaded catalog is held as a refusal for the interval. Decision 14's asymmetry holds: immediate on the node that served the write, within 5 s elsewhere. |
| 8 | Tenancy at the catalog | `RuntimePools:160-183` refuses `catalogs/` beside `tenancy.datasources` (`TQL-APP-4207`: "a catalog is held app-wide and is not yet keyed by tenant"); `lookups.md` slice 7 deferred "keying a hold by tenant" to the invalidation slice, which shipped without it. |
| 9 | The other holds in the tree | `CachingPreferenceStore` (core/account: 30 s, 512 subjects, LRU `LinkedHashMap`, injectable clock, keyed `tenantId + ' ' + subject`), `CachingInboxStore` (core/inbox: 15 s, an epoch bumped by every invalidation so a slow read-through cannot re-insert a pre-invalidation value), the JWKS key set. Local invalidation is immediate; another node's staleness is bounded by the TTL — the trade-off each documents. No generic hold exists; each is a decorator over one store. |
| 10 | Where a route's rows come from (`RouteCompiler.source:2094` → `NamedQueryBinder` → `SqlStep.process:106-201`) | `SqlRenderer.render(nodes, params, scopes, context, files)` → `BoundSql(sql, parameters, sourceMap, coverageTrace, variant)`; `parameters` are `BoundParameter(expression, value, sourceLine)`; the statement's pool is already routed (`SqlSource.Statement.dataSource`, `TenantRouting.dataSource:34-57` — the per-tenant pool replaces `main` only; a tenant with no pool is `TQL-TENANT-4031`); `SqlStatement.read` with `maxRows`/`onOverflow` → `{rows, rowCount, truncated?}` (`executeQuery:505-522`) → `ContextResults.put(context, key)`. A `page:` route appends the dialect clause and its parameters to the same `BoundSql` and may run a count. **Everything that changes the rows reaches the statement as one of three things: the pool, the rendered text (scope fragments are spliced into it), the positional binds (declared inputs, ambient `principal.*`/`tenant.*`, scope binds).** The framework sets no session state on a connection (`currentSchema` isolation is in the pool's URL). |
| 11 | What runs after the rows | `ResultDeclarationProcessor:85` copies each row before parsing declared kinds; `EnrichProcessor:70` copies the result map and `KeyedReference.compose` builds new rows; masking (`FieldPolicyApplier`) is applied to the response body at render, with the principal in hand; the HTML renderer negotiates the shell per request. Nothing between the statement and the renderer runs before the statement. |
| 12 | Enrichment (`KeyedReference.enrich:189-245`, `fetchSql:295-312`, `fetchHttp:321-350`, `EnrichProcessor`) | One `KeyedReference` per `enrich:` block, a fresh `Environment` per exchange (its connection, gateway and `tesseraql.enrich.degraded` counter); the distinct keys are batched by `batchSize` on one connection, or sent as `keys` (HTTP `batch`), or filled into the URL per key (`perRow`, `KeyedUrls.fill`); results merge into `Map<canonical key, rows>`. No state survives a block; the audit's verdict (`work/…/measure/docs-accuracy/verdict.md` F122) named the fix: "an exchange-scoped `Map<(datasource, sourcePath, canonical key), rows>` … a design question (memo key, sibling `from:`, HTTP `perRow`) for the record first." |
| 13 | The stance a cross-node cache must follow (`docs/deployment.md` "Safety valves and multi-node semantics", `TopicBus`, `docs/realtime.md:82-87`) | Limiters and lanes are per node by design; a rate limit may be cluster-wide through a row on the main database; live-view signals ride `pg_notify` on PostgreSQL and stay per node elsewhere, "best-effort by design". The catalog stamp is the database-backed pattern already in use: a row per unit on the main connector, polled on an interval. |
| 14 | Free numbers and the reserved one | `TQL-YAML-1077` is the next lint number (1076 is the filename judgement); `TQL-SEC-4142`, which `lookups.md` reserved for "a cached child query reads `/*%scope … */` or ambient `principal.*` without those in the cache key", is unassigned in the tree (4141 and 4143 exist). |
| 15 | How a declaration reaches lint, the editor and the reference | `UnknownKeyRules` derives accepted keys from the model records (`AcceptedKeys.of(shape)`), so a record component is what makes a key known (row 1's `1043` is that rule working); the JSON Schema (`tesseraql-defs-v1.schema.json` `binding` `:1004`, `enrich` `:349`) feeds the editor and `reference-yaml-surface.md`; `reference-config.md` is regenerated from `config.getString("tesseraql.…")` literals; the catalog's own `cache: {maxAge}` block (`CatalogSpec`, default `1h`, `tesseraql-catalogs-v1.schema.json:80`) is the one place the word already means "how long a load is held before the source is read again". |
| 16 | Bounds the hold inherits | `tesseraql.resultMaterialization.maxRows` defaults to **10,000** (`SqlDefaults.maxRows`, `SqlStatement.DEFAULT_MAX_ROWS`); `materialize.maxRows` overrides per source; `onOverflow: warn` marks `truncated`. A hold bounded by entries alone would admit ten thousand rows per entry. |

## The mechanism

A source that declares `cache:` has its statement's result **held** in one bounded, per-runtime
hold; the key is the pool, the tenant, the statement and every bind; a command's
`invalidates:` — the declaration catalogs already have, unchanged — drops the held entries that
read the named tables on this node and raises the same per-table version row the catalogs
read, so every other node drops them within the stamp interval. The HTTP `cache:` block is
untouched: it keeps describing the response to the client, and a `304` over a held source stops
costing a statement.

```
GET /products/dashboard                        POST /products/adjust  (command-json)
 │ RequestBinder → params, tenant, principal      │ steps … commit
 │ SqlStep (source byCategory, cache: 30s)        ├ TopicEmitProcessor      emit: [stock]
 │   render → BoundSql(sql, binds)                └ InvalidationProcessor   invalidates: [products]
 │   key = pool ⊕ tenant ⊕ statement ⊕ sql ⊕ binds      │ drops local holds whose tables ∋ products
 │   hold.get(key) ─hit─▶ rows (a copy) ──────┐         │ tql_catalog_version.products += 1
 │           │ miss (single-flight)           │         ▼ other nodes: next stamp read, ≤ 5 s
 │   execute → {rows,rowCount,truncated} → put│
 ▼                                            ▼
 declared kinds → enrich → view → render → HttpCacheProcessor (ETag, Cache-Control) — unchanged
```

Three things are new in the tree and one moves: a `ResultHold` in core (the bounded map, the
key, the TTL and stamp checks, single-flight, copy-on-read); a `HoldSpec` on `SqlStep`
(pipeline) carried from the declaration; an `Invalidations` bean the command processor calls
instead of the catalog store directly; and the stamp reader and writer of `JdbcCatalogStore`
move into a `TableVersions` component (operations, beside the DDL that names it) that the
catalog store and the hold both consult.

## The decisions

Each is recommended. The direction — the roadmap's sentence — is the user's; these are the shape
it takes against what row 4 through 16 found.

### 1 — What is held is the rows a statement produced, never the response

**Recommended.** The hold sits in `SqlStep`, below every renderer. Row 11 is why: masking runs
at render with the principal in hand, the HTML shell is negotiated per request, declared kinds
and enrichments copy before they change anything, a JSON body maps the rows per request. A
response cache would have to key on `HX-Request`, `Accept-Language`, the principal's masking
outcome and every header a template reads, and a miss in that list is a leak; a row hold keys on
what the statement saw (decision 3) and hands every later stage the same rows it would have
fetched. The HTTP `cache:` block keeps its meaning and its statelessness; the 0.6.0 sentence
becomes "a 304 saves transfer, and compute when the source is held".

Rejected: caching the rendered body per URL (the leak class above); caching at the
`SqlStatement` primitive for every surface (a command step's read would be held inside its own
transaction — decision 6 refuses that by declaration, and the primitive cannot know the recipe).

### 2 — Declared per source, opt-in, with the catalog's word: `cache: {maxAge, tables}`

**Recommended.** On a `sources:` entry, beside `sql:` and at the altitude of `enrich:` and
`result:` — "about the rows whatever fetched them" (`Binding`'s own Javadoc):

```yaml
sources:
  byCategory:
    sql:
      file: by-category.sql
    cache:
      maxAge: 30s                  # required: nothing is held unless asked, and for how long
      tables: [products, categories]   # required: what a writer's invalidates: names
```

`maxAge` is required — an opt-in with a default is a default. `tables:` is required for the
reason `TQL-FIELD-4621` refuses a `file:` catalog without one: "invalidation cannot find it, and
nothing parses SQL". An author who wants a time-only hold lists the tables and never invalidates
them; the cost is a list, and the hold that no write can reach is the class of defect this phase
exists to prevent. The word is `cache:` because the catalog already uses it for exactly this
meaning (row 15) and a second word for one concept is a documentation defect; the two altitudes
are told apart by their descriptions, and a route-level `cache:` under a source is still the
route's (row 1's `1043` names the path).

Rejected: a route-level `cache.result:` (a route has several sources, and the HTTP block and
the hold are different altitudes); a SQL comment directive `/*%cache 30s*/` (declarations are
YAML; directives are SQL); a default TTL for every read (correctness over hit rate).

### 3 — The key is the pool, the tenant, the statement and every bind; `TQL-SEC-4142` is withdrawn

**Recommended.** `ResultKey.of(datasource, tenantId, statementId, maxRows, onOverflow,
bound.sql(), bound.parameters())` renders one canonical string: the datasource **name**, the
resolved tenant id (`""` when none — `CachingInboxStore`'s spelling; per-tenant pools replace
`main` per tenant, so name plus tenant is the pool's identity), the statement's `id` (the
resolved file, dialect variant included), the row bound (it changes the rows), the rendered SQL
text and each parameter as `<type>:<text>` (`Integer:1` and `String:1` differ, `null` is its
own marker, `java.time` values print canonically). Row 10 is the argument that this is
complete: scope predicates are spliced into the text with their binds; ambient `principal.*` and
`tenant.*` are binds; a page's clause and cursor are text and binds; nothing else reaches the
statement, and the framework sets no session state a connection could carry. `SqlVariant` adds
nothing the text does not already say.

A bind that cannot be rendered canonically — `byte[]`, a stream, a collection — makes the
statement **unheld** for that request, counted as a bypass (decision 7), never a guess.

`TQL-SEC-4142` — reserved by `lookups.md` for a cached query that reads scope or principal
without them in the key — cannot fire: the key carries every effective bind and the rendered
text by construction. The number stays unassigned and the `lookups.md` row says so. Rejected: a
key of declared inputs only (a `/*%scope*/` predicate would be invisible to it — the leak 4142
was written for).

### 4 — One hold per runtime: bounded twice, single-flight, copy-on-read, errors never held

**Recommended.** `io.tesseraql.core.cache.ResultHold`, bound as `RESULT_HOLD_BEAN`, is the
`CachingPreferenceStore` shape made generic: an LRU `LinkedHashMap` with an injectable clock,
bounded by `tesseraql.cache.maxEntries` (default 1000) **and** by rows per entry,
`tesseraql.cache.maxEntryRows` (default 1000 — row 16: the source's own `maxRows` defaults to
ten thousand; a result larger than a thousand rows is a list to paginate, not a hold, and is
bypassed with a counted reason). A miss executes under a per-key lock so one statement runs for
N concurrent callers (`JdbcCatalogStore`'s `loadLocks`; Java 25 does not pin a carrier on a
monitor). A hit returns a fresh result map with a copy of every row — row 11 shows today's
stages copy on write, and the copy is what makes a hand-owned template or a future stage unable
to poison the hold. A statement that throws is not held; a `truncated` result is held with its
flag; the slow-SQL log records nothing on a hit because nothing executed. The held rows are the
database's values before `result:` parsing, which runs per request as it does now.

Entry staleness is checked in this order: TTL elapsed → the stamp of any of its tables moved
since the load (`stampAtLoad`, as the catalog) → serve. The stamp read is the shared reader's,
once per 5 s for every held table and catalog at once.

### 5 — Invalidation is the catalog's, extended: `invalidates:` reaches held sources through the same stamps

**Recommended.** The declaration does not change: `invalidates: [products]` on a command
(and on an MCP tool, where it already mounts). What it reaches does. The stamp reader and
writer of `JdbcCatalogStore` (row 7) move into `TableVersions` (operations, beside the DDL);
its `stampedTables` set becomes the union of every catalog's `sourceTables()` and every held
source's `tables:`; the catalog store and the `ResultHold` both ask it `versionOf(tables)`.
One `Invalidations` bean replaces the direct `CatalogStore` call in the processor: drop the
catalog holds, drop the result entries whose `tables` intersect, bump the rows — after the
commit, failures logged and swallowed, exactly as decision 13/14 of `lookups.md` argue for a
hint that is never the guarantee. Underneath sits `maxAge`, as the TTL sits under a catalog.

`tql_catalog_version` keeps its name: the row set is the same kind of thing (a version per
table), the DDL exists in three vendor dialects and is applied idempotently by every runtime,
and a rename would leave an orphan table in every deployed database for a word. Its Javadoc
and `code-catalogs.md` say it is the table-version table.

`TQL-FIELD-4620`'s warning widens: "names table `x`, which no catalog and no held source reads".
`LintContext.catalogTables()` becomes the union.

Rejected: a broker or `pg_notify` for invalidation (per-node stance; the stamp already exists
and works on every vendor); per-tenant stamp rows (a tenant's write drops every tenant's hold of
that table — over-invalidation is correct and cheap; a per-tenant row is an optimization with no
measured need); expression-valued keys (`lookups.md` decision 13's rejection stands).

### 6 — Where `cache:` is legal is judged once, on both altitudes: `TQL-YAML-1077`

**Recommended.** One predicate, `HeldSources` in `io.tesseraql.yaml.app` (the `RouteFiles` /
`FilenameTemplates` shape), reported by lint and refused by the compiler with the same
sentence. Legal: a `sql:` arm (file) in `mode: query` on a source of a **read** surface — a
`query-json`, `query-html` or `page` route, or an MCP tool that is not transactional. Refused,
one code, an arm each: on a transactional recipe (`command-json`, `webhook`, `queue-consume`:
a hold inside or after a write is a stale read of the write); on a `query-export` or
`file-export` route (its statement streams and is never materialized); on a `contract:`,
`service:`, `http:` or `spool:` arm (no statement text to key, or not a statement — an HTTP
reference gets its own hold in S2); with `mode: update` or `call`; `maxAge` absent,
unparseable or not positive; `tables:` absent, empty or carrying a blank name.

Two executions bypass the hold whatever the declaration says: the test runner
(`AppTestRunner`, `tesseraql test`/`coverage`) and Studio's live preview boot with
`tesseraql.cache.enabled: false`. A suite that writes and then reads must see the database, and
a preview is asked for the truth. `tesseraql.cache.enabled` (default `true`) is also the
operator's one-key answer when a hold misbehaves: every declaration bypasses, counted.

A live region (`refreshOn:`) over a held source refreshes to the hold unless the emitting
command also invalidates the tables. The docs say so beside `emit:`; a lint cannot, because
nothing declares which tables a topic stands for (filed).

### 7 — Observable: counters, a reason for every bypass, an operations surface with the catalog's shape

**Recommended.** Counters `tesseraql.cache.hits`, `.misses`, `.bypasses` (attribute `reason`:
`binds`, `oversize`, `disabled`), `.invalidations`, `.evictions`, each with `route` and `source`
— `Meter.counter`, the `tesseraql.enrich.degraded` naming. `GET /_tesseraql/ops/cache` reports
per declared source its tables, `maxAge`, live entries, hits and misses, and the stamp rows
(`table`, `version`, `updatedAt`); it reports the hold and never takes one, as
`CatalogStore.status()` does. `POST /_tesseraql/ops/cache/invalidate` with `{"tables": […]}`
drops and bumps through `Invalidations`, so every node follows; it requires
`tql.ops.run.<app>` like the catalog refresh, and an unknown table is the `OpsActions.notFound`
404. Both are JSON, as the catalog endpoints are; a console page is not in the slice (parity
with catalogs, which have none).

### 8 — The enrichment memo is request-scoped, per key, and the same key mechanism (F122, code M)

**Recommended (S2).** `KeyedReference.enrich` consults a memo held on the exchange — created
by `EnrichProcessor`'s per-request `Environment`, so it dies with the request — keyed by the
**reference identity** (`datasource + sourcePath` with its dialect for SQL; the call's `url`
template and host for HTTP) and the **canonical key tuple**. The second block fetches only the
keys the first did not, so row 3's four statements become two, and a page whose `main` and
`history` share a master costs one lookup per distinct key. The granularity is the key, not the
batch: a memo of batch statements would never hit when the second block asks a different key
set. `from:` (a sibling source) fetches nothing and is not memoized. The audit's guard is the
slice's: `EnrichIntegrationTest` with two blocks over one master, one `partners.sql` statement
per request (red on `main`: two), and a two-datasource fixture that a memo keyed by path alone
would conflate.

### 9 — An enrichment's cross-request hold is the same `ResultHold`, per key

**Recommended (S2).** `cache: {maxAge, tables}` on an `enrich:` entry puts each **key's** rows
into the runtime hold under the reference identity, the tenant and the key tuple — not the
batch — so a common partner is a hit across requests whatever the surrounding key set. SQL
references take `tables:` (required, as decision 2); an `http:` reference takes `maxAge` only
and `tables:` is refused (`1077`: nothing stamps a partner system) — its hold is time-bounded,
which is what a reference API's own `Cache-Control` would have said. `maxKeys` and
`batchSize` are unchanged: the memo and the hold shrink the key set before batching. The
degrade rule (`onError: empty`) is unchanged: a degraded fetch holds nothing.

### 10 — Tenancy: the key carries the tenant; the stamp does not; the catalog's own refusal stands

**Recommended.** Shared-schema tenancy is a `tenant.id` bind in the statement — the key
differs per tenant because the binds do; a statement that binds no tenant returns the same
rows for every tenant, held or not, which is the case `TQL-TENANT-3001` already warns about
and the hold neither creates nor hides. Per-tenant pools differ in the key by tenant id
(decision 3). The stamp row is per table, not per tenant (decision 5). `TQL-APP-4207` — catalogs
beside per-tenant pools — is not lifted by this record: a catalog is a whole-table hold
published under one name, and keying it by tenant is `lookups.md`'s own open item, not this
one's.

### 11 — A reload drops the hold

**Recommended.** `RouteReloader.reload` clears the `ResultHold` after a rebuild. A changed
statement is a new key anyway; a changed `tables:` must not leave entries filed under the old
declaration; the cost is the next request's statement. Local only — a deploy replaces every
node's runtime.

### 12 — The first consumer is the inventory dashboard, and the reference tells one story

**Recommended.** `examples/inventory-app` `products.dashboard` (six sources: totals, stock by
category and low stock on `main`; three lake reads on `analytics`) holds `byCategory` and
`lowStock` for `30s` over `tables: [products, categories]`, and `products.create` /
`products.adjust` declare `invalidates: [products]` — the first `invalidates:` in the gallery
(row 5) and the first held source, on the page a demo reloads most. The lake reads stay unheld
in S1: their writer is a job, which is S3's question. Docs: `response-shaping.md` gains
"Holding a result" beside "HTTP caching" with the interplay of the two spelled out;
`code-catalogs.md`'s `invalidates:` paragraph gains the held-source sentence;
`multi-tenancy.md` the key sentence; `deployment.md` the stamp under the multi-node stance;
`troubleshooting.md` `1077` and the widened `4620`; `lookups.md` decision 3, the non-goal and the
`4142` row point here; the schemas, their `.vscode` byte copies and the three generated
references follow. The extension changes nothing: completion is schema-driven, and no
extension source names `cache`.

## What this breaks

- **Nothing on the wire.** The HTTP `cache:` block, `invalidates:`, the catalog hold and the
  stamp table keep their names and meanings; `tql_catalog_version` gains rows for held tables
  the first time a command names one.
- **`TQL-FIELD-4620`'s sentence** widens ("no catalog and no held source reads"); a fixture
  asserting the old text flips.
- **`CatalogStore.invalidate`** stays for the ops refresh path, but the command processor no
  longer calls it directly: `CatalogInvalidateProcessor` becomes `InvalidationProcessor` over
  the `Invalidations` bean. Internal, pre-1.0, recorded.
- **`JdbcCatalogStore` loses its stamp code** to `TableVersions`; `JdbcCatalogStoreTest`'s
  stamp rows move with it.
- **S2 changes a count, not a value:** two `enrich:` blocks over one master cost one statement
  per request. No shipped test asserts the two (`EnrichIntegrationTest`'s detail fixture
  enriches `history` alone); the audit fixture (row 3) is the one that flips, and it becomes the
  guard.

## Filed, not fixed

- **`If-None-Match` as a list, `*`, or a weak tag** (`HttpCacheProcessor:57` compares one exact
  string). RFC 7232 allows all three; browsers send the one tag they hold. Filed against the
  HTTP block, not this half.
- **A response cache.** Rejected in decision 1, not deferred.
- **A lint for a live region over a held source** whose emitting command does not invalidate the
  tables (decision 6): needs a topic-to-table declaration that does not exist.
- **Per-tenant stamp rows** (decision 5): an optimization with no measured need.
- **Catalogs keyed by tenant** (`TQL-APP-4207`): `lookups.md`'s item.
- **Job steps' reads and the chunk reader's enrichment**: a job runs once; the chunk memo is
  S3's if S3 is named.
- **Plain `http:` sources** (`sources.x.http`): a call's cache is the gateway's business
  (`lookups.md` decision 15); the enrichment's HTTP hold (decision 9) is per key, which a plain
  source has none of.
- **Negative caching** (an empty result is held like any other; a *failed* statement never is).
- **A shared, cross-node hold.** Never: the per-node stance.

## The slices

### S1 — a source declares its hold; a command's `invalidates:` reaches it

`ResultHold` + `ResultKey` + `HoldSpec` (core `cache`), `TableVersions` + `Invalidations`
(operations), `HeldSources` + `1077` + the widened `4620` (yaml), `SqlStep` reads the hold,
`RouteCompiler` carries the spec and refuses at build, `RuntimePools` wires the bean and the
versions on the main connector, `InvalidationProcessor`, the two ops endpoints, the counters,
the reloader's clear, the runner's and the preview's `enabled: false`, the dashboard dogfood,
docs, schema, references, CHANGELOG.

| Guard | Proves | Red on the variant |
| --- | --- | --- |
| `ResultHoldTest` (core, NEW) | key rows: tenant, datasource, statement id, row bound, text, each bind type and `null`; TTL with an injected clock; stamp staleness through a stub `TableVersions`; LRU by entries; oversize bypass; single-flight (a latch, N callers, one load); copy-on-read (mutate the hit, read again); an error is not held; `truncated` is | `no-tenant-in-key`, `no-binds-in-key`, `no-copy`, `no-stamp`, `errors-held` |
| `TableVersionsTest` (operations, moved rows + the union) | `bump` inserts then increments; only stamped tables; `versionOf` reads once per interval; a failed read is TTL-only | `bump-any-table` |
| `HeldSourcesTest` + `AppLinterHeldSourcesTest` (yaml, NEW) | each `1077` arm; the legal shape lints clean; `4620` names a held source's table as read | `lint-noarm`, `4620-not-widened` |
| `HeldSourcesCompileTest` (compiler, NEW) | the same arms refused at build with the sentence; a legal source's `SqlStep` carries the spec; a command's never does | `boot-noarm` (the lint↔boot differential trap: feed the broken document to both) |
| `ResultHoldIntegrationTest` (runtime, NEW; the probe's method — PostgreSQL with `log_statement=all`, statements counted) | a held source logs one statement for N requests; `invalidates:` on the command drops it (the next request logs one); a second runtime on the same database follows within the interval (clock or a 6 s wait, one test); tenant A's rows are not B's (shared-schema `tenant.id` bind; per-tenant pools); a scoped statement is per principal; a page is per page; `truncated` held with its flag; the ops GET reports the hold, the ops POST drops and bumps; `enabled: false` bypasses; the `304` over a held source logs no statement | `no-tenant-in-key` (the tenant row alone), `no-drop-on-invalidate`, `no-bump` (the second runtime), `no-reload-clear` |
| `HttpCacheIntegrationTest` (+1 row) | the shipped block unchanged; the held twin logs no statement on revalidation | — |
| `InventoryAnalyticsIntegrationTest` (the IT that boots `inventory-app`, +1 row) | an adjust reflects on the dashboard at once | `no-invalidates-in-example` |

### S2 — the enrichment memo and the enrichment's hold

The exchange memo in `KeyedReference` (SQL, HTTP `perRow` and `batch`; not `from:`), `cache:`
on `enrich:` through the same `ResultHold` per key, `1077`'s two enrichment arms, counters with
`enrich` as the source, docs (`response-shaping.md` "Fetching a reference by key" gains the memo
sentence and the hold), the `lookups.md` decision 3 and F122 rows, CHANGELOG.

| Guard | Proves | Red on the variant |
| --- | --- | --- |
| `KeyedReferenceMemoTest` (yaml, NEW; a counting fetcher) | two blocks, one master: the second fetches the misses only; two datasources, same path: two fetches; HTTP `perRow`: one call per distinct key across blocks | `memo-by-path` |
| `EnrichIntegrationTest` (+3 rows) | the F122 fixture: one `partners.sql` statement per request; `seenRequests` across two HTTP blocks; a held reference across requests is a hit per key with a fresh key set | `no-memo`, `hold-by-batch` |
| `AppLinterHeldSourcesTest` (+2) | `cache:` on `from:` refused; `tables:` on `http:` refused | — |

### S3 — the other writers (if named)

`invalidates:` on a job definition (the pricing run that appends the lake), on a `file-import`
(the placement `emit:` already has at the import's commit) and on a `queue-consume`; the lake
reads of the dashboard held. Each mounts the same `InvalidationProcessor` after its commit. The
chunk reader's enrichment memo per chunk rides along if the slice is named.

## Docs and CHANGELOG

Each slice's PR: `CHANGELOG.md` **Added** (S1: "A source declares how long its rows are held,
and a command's `invalidates:` drops them"; S2: "Two enrichments over one master cost one
lookup per key, and a reference can be held") and **Changed** (`4620` widened; the version
table's row set; `CatalogInvalidateProcessor` → `InvalidationProcessor`, pre-1.0 internal);
`docs/response-shaping.md`, `code-catalogs.md`, `multi-tenancy.md`, `deployment.md`,
`troubleshooting.md`; the schemas and their `.vscode` copies; `reference-yaml-surface.md`,
`reference-error-codes.md`, `reference-config.md` regenerated **last**; the status block of this
record and the roadmap's Phase 32 paragraph updated as each slice lands. `lookups.md` is amended
by this record (decision 3, the non-goal, the `4142` row) and again by S2 (F122's code M);
`audit-low-leads.md` F122 by S2.

## Error codes

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-YAML-1077` | error, lint and build | `cache:` where nothing can be held: a transactional or streaming surface, a non-statement arm, `mode: update`/`call`, a missing, unparseable or non-positive `maxAge`, a missing or blank `tables:`; S2 adds `cache:` on `from:` and `tables:` on `http:` |
| `TQL-FIELD-4620` | error / warning (widened) | `invalidates:` on a recipe with no commit; a table no catalog **and no held source** reads |
| `TQL-SEC-4142` | — | reserved by `lookups.md`; withdrawn by decision 3, unassigned |
