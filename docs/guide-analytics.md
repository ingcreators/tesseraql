# Build reporting and analytics

Someone wants a dashboard, a monthly figure, or a spreadsheet they can send on. This guide is
the reading order for the reporting side of an application, where the work is aggregation and
presentation rather than transactions.

## The shape you are building

Numbers computed from data you hold — or from files you do not want to load — rendered as
charts and tables, exported on demand or on a schedule.
`examples/inventory-app` has the whole loop: a dashboard of stats, a bar chart, a low-stock
table, a supplier table read straight from CSV, and a trend from accumulated snapshots.

## The order to read

**1. Decide where the numbers live.**
If they are in your database, ordinary [2-way SQL](two-way-sql.md) is all you need. If they are
in files — CSV or Parquet drops, exports from another system — [duckdb.md](duckdb.md) makes
them queryable in place, with no load step.

**2. Read the whole loop once.**
[analytics.md](analytics.md) is the main page: files land, an ETL run summarizes them, history
accumulates, and pages read the result. It is worth reading end to end before building any one
part, because the parts are chosen together.

**3. Build the dashboard.**
[declarative-views.md](declarative-views.md) covers `view: dashboard` — stat tiles, charts, and
tables composed from a handful of SQL files with no HTML of your own.

**4. Let people take it away.**
[file-transfers.md](file-transfers.md) covers CSV and Excel exports as routes.
[printable-documents.md](printable-documents.md) adds PDF for the version that gets filed or
signed.

**5. Schedule the expensive part.**
A dashboard that aggregates three years of rows on every page load is a dashboard people stop
opening. [jobs.md](jobs.md) covers running the summarization on a schedule and storing the
result.

**6. Send it out.**
A report nobody opens is a report nobody reads. [notifications.md](notifications.md) covers
mailing the produced file when the run finishes.

## What people usually get wrong

- **Loading files that only need reading.** A CSV drop queried through DuckDB needs no table,
  no migration, and no import job.
- **Aggregating live on every request.** Summarize on a schedule, then read the summary.
- **Charting in the browser.** The dashboard view renders charts server-side from SQL results;
  no front-end build is involved.
- **One giant query.** Several named queries composed into one view read better and cache
  better than one query with six joins.

## Next

- [analytics.md](analytics.md) — the main page for this shape.
- [duckdb.md](duckdb.md) — querying files without loading them.
- [declarative-views.md](declarative-views.md) — dashboards without markup.
