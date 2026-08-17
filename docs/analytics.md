# Analytics, end to end

TesseraQL's analytics stack is the same three materials as the rest of the framework —
YAML declarations, 2-way SQL, and the managed runtime — applied to a different job:
files land, an ETL run summarizes them, history accumulates as queryable snapshots, a
dashboard renders beside the live tables, and a report leaves the building on a
schedule. No separate orchestrator, no separate BI deployment, no separate credential
store: the batch platform runs the ETL, the view layer draws the charts, and the
connector policy that governs every other ingress and egress governs these too.

This page walks the whole loop once, with pointers into each feature's own page. The
runnable reference is the bundled `examples/inventory-app` — every snippet below is a
lightly trimmed version of a file in it, and
`tesseraql dev --stack examples --app-name inventory --embedded-db` boots the entire story
([five-minute demo](five-minute-demo.md)).

## 1. Files become SQL

Declare a `duckdb` datasource beside `main` and partner files become tables
([DuckDB analytics](duckdb.md)):

```yaml
# config/tesseraql.yml
tesseraql:
  datasources:
    main:
      jdbcUrl: ${db.main.url}
      username: ${db.main.username}
      password: ${db.main.password}
    analytics:
      jdbcUrl: "jdbc:duckdb:"          # in-process query engine, never a system of record
      duckdb:
        extensions: [ducklake, postgres]
        attach:
          - { datasource: main, as: app, mode: readwrite }
        lake:
          catalog: main                # snapshot metadata lives on main
          data: data/lake              # Parquet files under a declared root
          mode: readwrite
        fileScopes:
          drops: { root: data/drops }  # where partner CSV/Parquet lands
```

SQL never names raw paths — the `${scope.*}` placeholder is the only way to a file,
and the engine's filesystem view is fenced to the declared roots. S3-backed lakes and
ad-hoc `${remote.*}` reads extend the same model to object storage.

## 2. The scheduled ETL

The nightly summarization is a [batch job](jobs.md) on the analytics datasource — a
calendar-gated schedule, plain SQL steps, and one statement that reads Parquet and
writes through the attach:

```yaml
# batch/pricing/load-price-summary.yml
trigger:
  schedule:
    cron: "0 0 4 * * ?"
    calendar: jp-market        # business-day calendars: holidays are data, not code
pipeline:
  - id: clear
    sql: { file: clear-price-summary.sql, mode: update }
  - id: load
    sql: { file: load-price-summary.sql, mode: update }
  - id: appendHistory
    sql: { file: append-price-history.sql, mode: update }
```

```sql
insert into app.price_summary (sku, best_price, suppliers)
select sku, min(price), count(distinct supplier)
from read_csv(/* ${scope.drops}/supplier-prices.csv */ 'data/drops/supplier-prices.csv')
group by sku
```

Runs are recorded per step with row counts, visible in the
[operations console](ops-console.md); reruns are safe by construction
(replace-the-window writes), and a failed run pages through the `sla:` policy.

## 3. History as snapshots — and time travel

`appendHistory` writes into a **lake table** — ACID snapshots over Parquet, metadata
on `main` ([lake tables](duckdb.md#lake-tables-ducklake-under-the-fence)). Every run
is a version, and any version stays queryable:

```sql
-- what did the summary look like as of snapshot 12?
select * from lake.price_history at (version => 12) where sku = 'MS-230';

-- the time-travel index
select snapshot_id, snapshot_time from ducklake_snapshots('lake');
```

A dashboard can render "as of the last close" beside "now" from the same table — no
copy, no export, one `AT (VERSION => n)` clause.

## 4. The dashboard

A [declarative dashboard](declarative-views.md#dashboard-views) composes live tables
and analytics queries in one page — each panel's query picks its datasource:

```yaml
# web/products/dashboard/dashboard.view.yml
version: tesseraql/v1
kind: view
recipe: dashboard
panels:
  - { type: stat, source: sql, column: products, label: Products }
  - type: chart
    chart: bar-grouped
    source: byCategory           # main: live stock vs reorder floor
    x: label
    series:
      - { column: stock, label: In stock }
      - { column: reorder, label: Reorder floor }
  - type: chart
    chart: line
    source: priceTrend           # analytics: the lake's history
    x: label
    y: avg_price
  - { type: table, source: lakeSnapshots, title: Lake snapshots }
```

Charts are the kit's [chart recipe](hypermedia-ui.md#charts): the server renders the
data table (the no-JavaScript fallback and the screen-reader representation), the
browser draws the SVG. `refreshOn:` makes the page live when a command commits.

## 5. The report leaves the building

The close produces a file and delivers it — three step bodies on one pipeline,
chained after the ETL ([the export step](jobs.md#the-export-step),
[the push step](jobs.md#the-push-step),
[notifications](notifications.md#the-notify-step-on-a-job)):

```yaml
# batch/pricing/daily-price-report.yml
trigger:
  after: pricing.loadSummary
pipeline:
  - id: report
    sql: { file: price-report.sql, mode: query }   # the step's arm reads
    export:                                        # export: says how it is written
      format: csv                                  # or excel / pdf
      filename: price-summary-{batch.businessDate}.csv
  - id: deliver
    push:
      transport: local                             # or sftp / ftps, allow-listed
      path: outbox/reports
      file: steps.report.transferId
  - id: announce
    notify:
      channel: reports                             # a mail channel
      attach: steps.report.transferId               # the file rides the mail
      payload:
        rows: steps.report.rows
```

Every produced file is a tracked transfer: listed and downloadable on the operations
console's transfers page, expirable by
[retention policy](file-transfers.md#retention), and attachable or deliverable
without ever writing a credential in a job — push targets and mail channels draw on
the deny-by-default [connector policy](connectors.md).

## 6. Governing it

The same machinery that governs the rest of an app covers the analytics surface with
nothing extra to configure:

- **Admission** surfaces every DuckDB datasource, extension list, write-mode attach,
  remote lake endpoint, and egress allow-list ([marketplace admission](admission.md));
  a bare `*` in any allow-list fails it.
- **Lint** checks the whole loop at build time — file placeholders, calendar
  references, step shapes, chart vocabulary, push targets — and the
  [error-code reference](reference-error-codes.md) indexes every refusal.
- **Studio** browses every declared datasource — on the analytics engine, across the
  attached catalogs and lake tables — and the declarative tests cover ETL SQL like
  any other SQL ([testing](testing.md)).

## Where each piece is specified

| Piece | Page |
| --- | --- |
| Files, scopes, lake tables, S3 | [DuckDB analytics](duckdb.md) |
| Jobs, calendars, business dates, chunking | [Jobs and scheduling](jobs.md) |
| Dashboards and charts | [Declarative views](declarative-views.md), [Hypermedia UI](hypermedia-ui.md#charts) |
| Export, transfers, retention | [File import and export](file-transfers.md) |
| Push targets, poll sources, egress policy | [Managed connectors](connectors.md) |
| Mail attachments | [Notifications](notifications.md) |

## Next

- [guide-analytics.md](guide-analytics.md) — the reading order for the reporting side of an application.

- [duckdb.md](duckdb.md) — the engine behind the lake tables.
- [jobs.md](jobs.md) — scheduling the summarization.
- [declarative-views.md](declarative-views.md) — the dashboard views that render it.
