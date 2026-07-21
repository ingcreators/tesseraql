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
- Local tables are correspondingly scratch space: an ETL statement may stage into
  them freely, but anything worth keeping leaves through an attached datasource or an
  export before the process ends.

## Reading files through scopes

SQL never names raw filesystem paths dynamically. Exactly two placeholder channels
resolve to a path — **file scopes** (below) and **datasets** (later section) — and
everything else must be a static literal under a declared scope root; string
concatenation or any other expression in a file-function argument is refused at lint
time. This is the same discipline the redirect placeholders follow: user input never
reaches a path position.

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
tesseraql duckdb install-extensions --app . --bundle duckdb-ext.tar.gz   # air-gap bundle
```

- The command derives the DuckDB version and platform from the bundled driver — the
  single source of truth — so a driver upgrade with a stale cache is caught at boot,
  and `tesseraql info` reports the pin (the embedded-PostgreSQL binary discipline).
- The cache uses DuckDB's own repository layout
  (`<version>/<platform>/<name>.duckdb_extension`), so no repackaging exists to
  drift; integrity is DuckDB's offline signature verification, not a parallel
  checksum scheme.
- `--repository <url>` points the fetch at a corporate mirror; `--bundle` writes a
  portable archive to carry across an air gap, unpacked at
  `tesseraql.duckdb.extensionDirectory` on the target.

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
# jobs/sales/load-summary.yml
version: tesseraql/v1
id: sales.loadSummary
kind: job
recipe: batch-tasklet
datasource: analytics

trigger:
  schedule:
    cron: "0 0 4 * * ?"

sql:
  file: load-summary.sql
  mode: update
```

```sql
INSERT INTO app.sales_summary (category, total, loaded_at)
SELECT category, sum(amount), now()
FROM read_parquet(/* ${scope.sales}/monthly.parquet */ 'data/sales/acme/monthly.parquet')
GROUP BY category
```

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
When files need per-user grants — uploads a user analyzes, reports shared to a role
— the reference moves up a level: a **dataset catalog on `main`** maps an id to a
blob (or scoped path) plus its owner, allowed roles, tenant, and scan state. Routes
declare a `type: dataset` input; the runtime resolves the id through the catalog
*under the caller's identity*, refuses anything the caller cannot see, and never
resolves an upload the [attachment scanners](attachments.md) have not passed. The
SQL sees only the second placeholder channel:

```sql
SELECT * FROM read_parquet(/* ${dataset.report} */ 'dummy.parquet') LIMIT 100
```

Row-level restriction inside a file is ordinary SQL — predicates on the caller's
subject or tenant, or an entitlement join against the attached `main` (with
`filename=true`, a glob's matched files join like any column) — the same
responsibility split as tables.

How the bytes reach the engine is the runtime's choice per blob-store backend:

- **Filesystem store: zero copy.** The blob is already a local file; its exact path
  is handed to the engine read-only.
- **Any store: spool.** The blob streams into the scratch directory once,
  content-hash keyed, single-flight under concurrency, size-capped and TTL-swept —
  the temp-store machinery, reused.
- **S3-compatible store: presigned read.** With `httpfs` enabled, the store's
  short-lived presigned GET URL is read directly — the engine holds no S3
  credentials, and Parquet's column/row-group pushdown fetches only the bytes the
  query needs. Egress stays inside the outbound allow-list the admission profile
  already checks (`TQL-ADM-4703`); without `httpfs`, the spool path serves the same
  query unchanged — offline deployments lose nothing but the pushdown.

## The security stance

- **No network at runtime**: extensions are pre-provisioned, signed, and loaded from
  the local cache; `httpfs`, when enabled, reads only presigned URLs for cataloged
  datasets under the egress allow-list.
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
