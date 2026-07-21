# DuckDB analytics: files as SQL sources

A `duckdb` datasource embeds the [DuckDB](https://duckdb.org) engine inside the
runtime process and makes CSV and Parquet files queryable with plain SQL — on pages,
views, dashboards, exports, and scheduled ETL jobs — through the same `datasource:`
word that selects any other [named datasource](multi-datasource.md). One stance
shapes everything: **DuckDB is a query engine here, never a system of record.**
Durable state lives on `main` (or another server datasource) and in the
[blob store](attachments.md); the DuckDB side holds no schema to migrate, no
framework table, and no state another cluster node would miss.

Nothing in this design depends on a server-side database extension: operators who
run their own PostgreSQL may install pg_duckdb or an FDW and call it from route SQL
(statements pass through unmodified), but the framework never requires it.

## Declaring a DuckDB datasource

```yaml
# config/tesseraql.yml
tesseraql:
  datasources:
    main:
      jdbcUrl: ${db.main.url}
      username: ${db.main.username}
      password: ${db.main.password}
    analytics:
      jdbcUrl: jdbc:duckdb:            # in-process, in-memory
      duckdb:
        fileScopes:
          sales:
            root: data/sales
            partitionBy: tenant        # resolves to data/sales/<tenant>/…
```

- **The dialect is `duckdb`,** inferred from the `jdbcUrl` prefix like every other
  dialect and baked per route (pagination, streaming, label rules); its SQL surface
  is PostgreSQL-family (`LIMIT`/`OFFSET` pagination).
- **The driver arrives through the module channel, not the CLI fat jar.** DuckDB's
  JDBC artifact bundles native libraries for every platform and is far too large to
  ship by default; `tesseraql modules add org.duckdb:duckdb_jdbc --app .` pins it in
  `modules.lock` exactly as the Oracle driver in [getting started](getting-started.md).
- **In-memory by default.** A file path in the URL (`jdbc:duckdb:./cache.duckdb`) is
  legal but node-local and disposable — cache semantics, never shared between nodes,
  never backed up. A `db/<name>/migration` tree for a DuckDB datasource is refused at
  build time: there is nothing durable to migrate.
- **Each pooled connection is its own in-memory database** (a property of the DuckDB
  JDBC driver this design leans into: nothing shared, nothing durable). The pool
  defaults to `maximumPoolSize: 4` so a deployment cannot silently multiply engine
  memory by the connection-pool default; `duckdb.memoryLimit` and `duckdb.threads`
  pass through as per-connection engine settings.
- Local tables are correspondingly scratch space: an ETL statement may stage into
  them freely, but anything worth keeping leaves through an attached datasource or an
  export before the process ends.

## Reading files through scopes

SQL never names raw filesystem paths. Exactly two placeholder channels resolve to a
path — **file scopes** (below) and **datasets** (later section) — and a file-reading
function taking anything else (a literal, concatenation, any expression) is refused
at lint time; the connection fence refuses it again at runtime, under a locked
configuration, as defense in depth. This is the same discipline the redirect
placeholders follow: user input never reaches a path position. (Raw literals are not
merely discouraged: under the fence a relative path has no defined base directory,
so the placeholder channel is the only portable form.)

A scope declares a root directory; `partitionBy: tenant` inserts the resolved tenant
of the request between root and file, so one declaration isolates every tenant's
drop directory. Resolution rejects `..`, absolute paths, `//`, and the backslash
forms outright. In 2-way SQL the placeholder carries a dummy literal so the
statement stays runnable in a plain SQL tool:

```sql
SELECT category, sum(amount) AS total
FROM read_parquet(/* ${scope.sales}/monthly.parquet */ 'data/sales/acme/monthly.parquet')
GROUP BY category
ORDER BY total DESC
```

A route (or a named read query on a page) opts in with `datasource: analytics` and
composes with result sets from `main` exactly as [multi-datasource
reads](multi-datasource.md) already do — a dashboard can chart a Parquet aggregation
beside a live table with no new vocabulary.

The engine's own filesystem access is fenced to match: external file access is
enabled only when scopes are declared, and constrained (DuckDB `allowed_directories`)
to the scope roots plus the runtime's scratch directory.

## Extensions, provisioned offline

CSV and Parquet need no extension — they are statically linked into the driver. The
extensions that widen the engine (`postgres`, `httpfs`) are opt-in per datasource:

```yaml
      duckdb:
        extensions: [postgres]
        attach:
          - { datasource: main, as: app, mode: readwrite }
```

The provisioning stance is **offline-first: the runtime never downloads.**
`autoinstall` and `autoload` are off, `allow_unsigned_extensions` is never set, and
extensions load at connection setup from a local cache only; a declared extension
missing from the cache fails the boot fast, naming the command that fixes it.

```console
tesseraql duckdb install-extensions --app .              # resolve + verify into the cache
tesseraql duckdb install-extensions --app . --bundle duckdb-ext.zip       # write an air-gap bundle
tesseraql duckdb install-extensions --app . --from-bundle duckdb-ext.zip  # populate offline from one
```

- The command derives the DuckDB version and platform from the bundled driver — the
  single source of truth — so a driver upgrade with a stale cache is caught at boot,
  and `tesseraql duckdb info` reports the pin and the cache contents (the
  embedded-PostgreSQL binary discipline).
- The cache uses DuckDB's own repository layout
  (`<version>/<platform>/<name>.duckdb_extension`, default
  `work/duckdb-extensions`, relocatable via `tesseraql.duckdb.extensionDirectory`),
  and installation runs through the engine's own `INSTALL` — so no repackaging
  exists to drift, and integrity is DuckDB's offline signature verification, not a
  parallel checksum scheme.
- `--repository <url>` points the fetch at a corporate mirror; `--bundle` writes the
  cache as a portable zip to carry across an air gap, and `--from-bundle` populates
  the cache from one with no network at all.

## Attaching other datasources

`attach:` lists **declared datasources**, and the runtime performs the `ATTACH` at
connection setup with credentials taken from the datasource declaration — SQL never
carries a connection string, and no DuckDB-persisted secret exists. The alias
defaults to the datasource name; attaching `main` requires an explicit `as:` because
DuckDB's own default schema is already called `main`.

- **Read-only by default.** `mode: readwrite` is a per-attach opt-in; the
  [admission profile](admission.md) surfaces every write-mode attach.
- Attached connections are the engine's own, outside the Hikari pools and their
  limits — the datasource block caps them separately, and a long analytical scan
  holds its server connection for the duration; size accordingly.
- A PostgreSQL attach requires the `postgres` extension above.

The attach is what turns file reads into one-statement ETL. The pull-shaped batch —
"load this Parquet drop into a summary table" — is a scheduled [job](jobs.md) on the
DuckDB datasource writing through the attach:

```yaml
# batch/sales/load-summary.yml
version: tesseraql/v1
id: sales.loadSummary
kind: job
recipe: batch-pipeline
datasource: analytics

trigger:
  schedule:
    cron: "0 0 4 * * ?"

pipeline:
  - id: clear
    sql: { file: clear-summary.sql, mode: update }
  - id: load
    sql: { file: load-summary.sql, mode: update }
```

```sql
INSERT INTO app.sales_summary (category, total, loaded_at)
SELECT category, sum(amount), now()
FROM read_parquet(/* ${scope.sales}/monthly.parquet */ 'data/sales/acme/monthly.parquet')
GROUP BY category
```

(The `clear` step deletes the window `load` re-fills — the replace-the-window shape
the transaction note below demands.)

The scheduler is cluster-safe, so the job runs on one node and the single-writer
constraint is satisfied by construction; the durable result lands on `main`. A
statement that writes through an attach commits on the *target* engine — DuckDB and
the attached database are two engines, not one transaction — so ETL jobs follow the
projection discipline: idempotent upserts or replace-the-window writes, safe to
re-run.

The push-shaped, continuous direction is unchanged: a business write on `main`
reaches another *server* datasource as a [projection](multi-datasource.md), and a
DuckDB datasource is **not** a projection target — it holds nothing durable.
Exports run the other way: `COPY (…) TO` a scratch file, stored through the
[blob store](attachments.md) as a durable, shareable object.

## Datasets: files with an owner

Scopes cover directory-shaped data (tenant drops, operator-managed report files).
When files need per-user gating — uploads a user analyzes — the reference moves up
a level: **every managed [attachment](attachments.md) is addressable as a dataset
by its id**, and the attachment metadata on `main` is the catalog: owner
(`created_by`), scan state, checksum, blob key. A route binds the caller-supplied
reference through an ordinary `params:` entry; the runtime resolves it *under the
caller's identity* — the attachment must exist, belong to the authenticated
principal, and have passed the scanners, and every refusal is the same neutral
answer, so the channel never confirms whether a guessed id exists. The SQL sees
only the second placeholder channel:

```sql
SELECT * FROM read_parquet(/* ${dataset.report} */ 'dummy.parquet') LIMIT 100
```

Row-level restriction inside a file is ordinary SQL — predicates on the caller's
subject or tenant, or an entitlement join against the attached `main` (with
`filename=true`, a glob's matched files join like any column) — the same
responsibility split as tables.

How the bytes reach the engine is one mechanism for every blob-store backend: the
**dataset spool** (`work/duckdb-spool`, relocatable via
`tesseraql.duckdb.spoolDirectory`) — the blob streams to a local content-addressed
file once, written atomically so concurrent localizations converge, touched on
every hit and swept least-recently-used past a cap. The spool is the one directory
the fence admits beyond the scope roots, which is exactly why it is the only
bridge: a filesystem blob store could serve zero-copy, and an S3 store could serve
a presigned `httpfs` read with Parquet range pushdown, but the first would open the
whole blob root to SQL and the second cannot coexist with
`enable_external_access=false` — both are recorded as possible future tiers, not
current behavior.

## Lake tables: DuckLake under the fence

Scopes read files as they land and datasets gate uploads; **lake tables** are the
managed middle: real tables over Parquet, with ACID snapshots, schema evolution,
and time travel — via [DuckLake](https://ducklake.select), whose whole design is
that lakehouse *metadata lives in an ordinary SQL database*. That database is a
declared PostgreSQL datasource (`main` by default), which keeps the framework's
stance intact where it matters: **the engine stays stateless**; what becomes
durable is ordinary rows on a datasource operations already governs, plus Parquet
files under a declared directory.

```yaml
    analytics:
      jdbcUrl: "jdbc:duckdb:"
      duckdb:
        extensions: [ducklake, postgres]
        lake:
          catalog: main          # the PostgreSQL datasource holding the metadata
          schema: ducklake       # its tables, confined to this schema on the catalog
          data: data/lake        # Parquet files, fence-admitted like a scope root
          as: lake
          mode: readwrite        # readonly for reporting-only deployments
```

The runtime performs the DuckLake attach at connection setup — credentials from
the catalog datasource's declaration (following the `--embedded-db` override, like
any managed attach), the metadata confined to the named schema, the data directory
joining `allowed_directories` — and then the same fence drops. Everything below is
proven against DuckDB 1.3.1 with the fence locked:

- **Writes are multi-connection safe.** Every pooled connection (and so every
  node) is its own engine, yet commits serialize through the catalog: one
  connection's committed insert is immediately visible to another, and concurrent
  writers both land as consecutive snapshots. The single-writer constraint that
  shapes plain duckdb ETL does not apply to lake tables.
- **Every job run is a snapshot.** `AT (VERSION => n)` reads a prior state — a
  dashboard can render "as of the last close" beside "now" — and
  `ducklake_snapshots('lake')` lists the history.
- **Maintenance is explicit.** `ducklake_expire_snapshots` and
  `ducklake_cleanup_old_files` run as an app-declared batch job on the same
  datasource; retention policy belongs to the app, and nothing expires by
  default.
- **The catalog schema is self-managed.** The extension owns the `ducklake` schema
  on the catalog datasource; app migrations must not touch it, and Flyway never
  will (it manages only `db/migration` trees).

One honest constraint stands until the S3 tier ships: in a multi-node deployment
the `data:` directory must be storage every node can read — shared storage, or a
single analytics node. Remote (S3) data paths arrive with the inverted-fence lake
tier, which DuckLake makes worth building: it turns that tier from ad-hoc reads
into governed, transactional writes on object storage.

## The security stance

- **No network at runtime**: extensions are pre-provisioned, signed, and loaded from
  the local cache, and dataset bytes reach the engine through the local spool — the
  engine itself never opens a network connection beyond its declared attaches.
- **No dynamic paths outside the two channels**; scope resolution is
  traversal-proof, and the engine's filesystem view is fenced to scope roots plus
  scratch.
- **No credentials in SQL**: attaches are framework-managed from declared
  datasources; presigned URLs replace S3 keys.
- **Nothing durable, nothing shared**: framework bookkeeping stays on `main`
  (`TQL-YAML-1036` and its compile-time guard apply to DuckDB routes as to any
  non-main route); engine state is per-node scratch.
- **Admission-visible**: a DuckDB datasource, its extension list, every write-mode
  attach, and `httpfs` each surface in the admission report.

## Deliberately out of scope (documented, not implied)

- **A shared or durable `.duckdb` database** — multi-node file sharing, backup, or
  DuckDB as a tenant home. The engine is compute, not storage.
- **DuckDB as a projection target** — projections materialize into server
  datasources; DuckDB materializes into exports.
- **Runtime `INSTALL` and unsigned extensions** — provisioning is a deliberate,
  offline-capable operator step, and the signature requirement never relaxes.
- **Cross-datasource SQL between server datasources** — the
  [multi-datasource](multi-datasource.md) stance stands; `attach:` is the one
  declared exception, and it lives inside the DuckDB engine under the read-only
  default.
- **Studio's data browser and schema introspection over DuckDB** — they stay on
  `main` for now, as for other non-main datasources.
