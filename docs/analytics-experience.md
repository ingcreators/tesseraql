# Analytics experience: browse, chart, deliver

The [DuckDB analytics stack](duckdb.md) shipped whole — file scopes, datasets, lake
tables, remote reads, one-statement ETL through `attach:` — and the
[batch platform](batch-platform.md) gives it calendars, checkpoints, and a console.
What keeps "SQL ETL over Parquet, dashboards beside live tables" from being a
first-class story is not the plumbing but the experience around it, and the gaps are
three, each named as out of scope by the design that created it:

1. **You cannot see what you built.** Studio's data browser and its introspection stay
   on `main` ([duckdb.md](duckdb.md) out-of-scope list), so a lake table or a reporting
   replica is invisible to the surface whose whole job is looking at data.
2. **Charts stop at one series.** The dashboard `chart` panel renders a single-series
   bar or line as a 320×120 server-side SVG — honest, but not an analytics dashboard.
   Hypermedia Components 0.1.9 ships a full chart recipe the framework predates and
   does not use.
3. **A job cannot produce a file.** `export:` is route vocabulary; the scheduled
   "close the day, write the report" move has no home. A job's only file-shaped output
   is a JSONL spool or a DuckDB `COPY TO` into scratch.

One stance shapes all three tracks: **no new machinery where shipped machinery
reaches.** The browser generalizes over the datasource map it already receives; charts
adopt the kit's contract instead of growing a second renderer (AGENTS.md rule 11); the
export step reuses the codec + spool + transfer bookkeeping the `file-export` recipe
already runs on.

## Track 1 — The data browser over named datasources

The browser's service already takes `Function<String, DataSource>`; it simply passes
`"main"` at every call site. The change is to make the datasource a browsing dimension:

- **A `datasource` selector** joins the table selector, offered only when the app
  declares more than one datasource. Every declared datasource is browsable — server
  databases (the reporting replica case) and `duckdb` engines alike. The parameter is
  validated against the declared-datasource set the way the table name is validated
  against the live catalog: membership or refusal, never interpolation.
- **DuckDB listings are qualified.** On a server datasource the table list stays what
  it is today: the connection's own catalog. A `duckdb` connection's own catalog is
  empty scratch by design — everything interesting is an attach (`app`), a lake
  (`lake`), or another mounted catalog — so on a `duckdb` datasource the browser lists
  tables and views across every catalog visible on the connection, displayed and
  addressed as `catalog.schema.table`. Each identifier part is quoted independently;
  validation remains list-membership, so a qualified name can never be an injection
  vector any more than a bare one could.
- **The read-only discipline holds, with driver honesty.** Browse and CSV export keep
  the statement timeout, the scan cap, and bound-parameter filters on every
  datasource. `Connection.setReadOnly(true)` stays best-effort — a driver that does
  not support it (DuckDB's returns quietly) does not fail the browse, because the
  browser's own SQL surface is `SELECT` over validated identifiers; read-only was
  always defense in depth, not the boundary.
- **Row editing stays on `main`.** Non-main data is derived data — a projection, a
  replica, a query engine's view of files. An edit affordance there is a footgun, not
  a feature; the editor, its roles, and its audit trail do not grow a datasource
  parameter.
- **One opt-in covers the browser.** `tesseraql.studio.dataBrowser.enabled` already
  means "expose row data to Studio users"; that decision does not change per
  datasource, so no second switch appears. The docs say plainly that the opt-in spans
  every declared datasource.

Out of scope, named: the docs portal's `schema.json` sidecar stays a `main`-catalog
introspection. It is a build-time artifact, and a DuckDB catalog only exists on a live
connection with its attaches performed — there is nothing to introspect at build time.
The live browser *is* the introspection surface for non-main datasources.

## Track 2 — Charts become the kit's charts

`ChartSvg` was written when the kit had only a CSS skin (`hc-chart`) and no chart
behavior. Hypermedia Components 0.1.9 ships the real thing — `dist/chart.js`, the
`data-hc-chart` contract — and rule 11 is unambiguous about which side owns chart
rendering now. The framework adopts the kit recipe and deletes its own renderer.

**The kit contract** (the [chart recipe](https://ingcreators.com/hypermedia-components/recipes/chart/)):
a `<figure class="hc-chart" data-hc-chart="<kind>">` containing a real
`<table class="hc-table">` with a `<caption>`. The table *is* the data source, the
no-JavaScript fallback, and the screen-reader representation; `installChart()`
enhances it into an Observable Plot SVG on load and on every htmx swap, and is a
no-op when Plot is absent. Column one is the x axis; every further column is a
series; `<th data-mark="bar|line|area">` assigns per-series marks for combo charts.

What the framework does with it:

- **The dashboard `chart` panel renders the kit markup.** The server emits the figure
  and the source table — a pure function of the query rows, so reproducibility
  (principle 4) is untouched; what changes is that the *chart* is now drawn
  client-side from that table. `ChartSvg` is deleted outright (pre-1.0, no shims);
  the emitted-markup change is recorded as breaking in the CHANGELOG.
- **Observable Plot is vendored, not CDN'd.** `org.webjars.npm:observablehq__plot`
  joins the self-hosted vendor set (its UMD bundle is self-contained and defines
  `window.Plot`), served at the version-less `/assets/vendor/` path like htmx and the
  kit itself. The CSP stays exactly `default-src 'self'` — nothing relaxes.
- **Charts load only where charts are.** `installChart` is deliberately outside the
  kit's auto-init bundle (it needs Plot), so the dashboard fragment — not the shell —
  emits the Plot script tag plus a small framework module
  (`/assets/_tesseraql/charts.js`) that imports `installChart` from the kit bundle
  and installs it with `window.Plot`. Pages without chart panels ship not a byte of
  charting. `installChart` listens for `htmx:load`, so `refreshOn:` live dashboards
  re-render their charts with no extra wiring.
- **The panel vocabulary grows to the kit's.** `kind:` accepts the kit set — `bar`,
  `line`, `area`, `combo`, `bar-stacked`, `bar-grouped`, `scatter` (`histogram` and
  `heatmap` stay out until a gallery app needs them). Multi-series arrives as
  `series:` — a list of `{column, label?, mark?}` — with the existing single-column
  `y:` kept as its shorthand; `mark:` is legal only under `kind: combo`. New optional
  panel keys pass through as the kit's data attributes: `xType:`
  (`category|number|date` → `data-x-type`), `height:` (→ `data-height`), `legend:`
  (→ `data-legend`), `yLabel:` (→ `data-y-label`).
- **The `sparkline` panel is unchanged.** It renders the kit's CSS `hc-sparkline`
  component today and keeps doing so — no Plot dependency for a 48px trend.

The `docs/declarative-views.md` dashboard section and `docs/hypermedia-ui.md` gain the
pattern (the latter had no charts section at all); the inventory gallery dashboard
upgrades to a grouped multi-series chart over its Parquet-backed query, which makes it
the runnable proof for both this track and the DuckDB story.

## Track 3 — The export step

A pipeline step gets a fifth body, `export:` — the route recipes' export vocabulary,
verbatim, on a scheduled job:

```yaml
# batch/pricing/daily-report.yml
pipeline:
  - id: refresh
    sql: { file: load-summary.sql, mode: update }
  - id: report
    export:
      format: excel
      filename: price-summary-{batch.businessDate}.xlsx
      sql: { file: report.sql, mode: query }
      columns:
        - { name: category, header: Category }
        - { name: total, header: Total, type: number, format: "#,##0" }
  - id: announce
    notify:
      channel: reports
      payload:
        rows: step.report.rows
        transferId: step.report.transferId
```

- **Same machinery, synchronous shape.** The step runs through the transfer service's
  extraction path — codec streaming from a JDBC cursor into the `TempStore` spool, a
  `tql_file_transfer` row recording format, filename, row count, and spool URI — but
  inline on the job's thread and the job's datasource (the 202-then-poll dance is the
  HTTP shape, not the codec's). The transfer's route id is `<jobId>#<stepId>`, so the
  ops transfers page tells job-produced files from route-produced ones at a glance.
- **The job's datasource is the step's datasource.** `export.sql.datasource` is
  refused on a step for the same reason `TQL-YAML-1037` refuses it on step SQL. On a
  `duckdb` datasource this is the payoff move: `report.sql` reads Parquet, lake
  tables, or an attach, and the codec writes CSV, Excel, or PDF — the analytics
  report in one step. File placeholders resolve through the same per-datasource
  resolver step SQL already uses.
- **Templates resolve beside the job.** `template:` (Excel placement/jxls, PDF XHTML)
  resolves relative to the job's directory, exactly as a route's resolves beside the
  route.
- **`after:` keeps its meaning.** `timing: extract` runs in the extraction
  transaction (mark-as-reported belongs with the read); `timing: download` runs on
  first fetch, unchanged, since the transfer machinery is shared.
- **The step publishes what later steps need.** `step.<id>.transferId`, `.rows`, and
  `.filename` land in the step context, so a follow-up `notify:` carries the pointer
  (an inbox or mail message linking the console) and a follow-up `http-call:` can
  tell a partner system the drop is ready.
- **Retrieval is the ops console.** The transfers page already lists every
  `tql_file_transfer` row under `ops.batch.view`; it gains a download action through
  the same service `download()` the route endpoints use, policy-gated like the rest
  of the console. A route-produced transfer keeps its route-scoped download URL; the
  console action covers the job-produced ones that have no route.

Out of scope, named so they stay decisions:

- **Mail attachments.** `MailNotifier` sends single-part bodies; attaching a produced
  file is a real feature with size, retention, and dead-letter questions — if demand
  lands, it is designed against the outbox, not bolted on here. The link-in-mail
  composition above covers the common case.
- **SFTP/FTPS push.** Poll sources are deliberately consume-only; a push connector is
  an egress surface needing the same allow-list + credential + admission treatment
  poll sources got, designed as its own piece when a real integration demands it.
- **Parquet as a codec.** Parquet stays DuckDB's format (`COPY TO`, lake tables); a
  `FileCodec` for it would duplicate an engine the framework already embeds.

## Slices

| # | Track | Ships |
| --- | --- | --- |
| 1 | Browse | `StudioDataService` datasource parameter + qualified DuckDB listings, browser UI selector, docs, ITs against Postgres + DuckDB |
| 2 | Chart | Plot webjar + `charts.js`, panel vocabulary (`series:`, kit kinds, passthrough attrs), kit-markup rendering, `ChartSvg` deletion, lints, gallery + docs |
| 3 | Deliver | `export:` step body (model, lint, executor), transfer rows + console download, step-context outputs, gallery job + docs |

Each slice is independently shippable in that order; nothing in a later slice blocks
an earlier one.

## Machine-checkable surface

- `TQL-VIEW-3313` — chart panel vocabulary violations: unknown `kind:`, `series:`
  and `y:` both present, `mark:` outside `kind: combo`, or a series column the
  panel's source cannot supply being structurally absent (columns are checked at
  render time like every panel column today).
- `TQL-FIELD-2004` — extended message: exactly one of `sql:`, `notify:`,
  `http-call:`, `chunk:`, or `export:`.
- Export-step reuse: the pdf template lints (`TQL-YAML-1005/1006`) apply to step
  exports as to route exports; `export.sql.datasource` on a step is refused under the
  `TQL-YAML-1037` rationale; runtime codec/transfer errors keep their `TQL-LD-28xx`
  codes.
- The browser's datasource parameter fails closed: an undeclared name is the same
  neutral refusal an unknown table gets.
