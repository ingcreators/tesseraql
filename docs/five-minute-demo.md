# Five-minute demo

One command boots a complete, seeded, browsable app with Studio open.

## What you need

The gallery apps live in the framework repository — clone it for the **app files only**; you
run them with your installed CLI and never build the framework:

```bash
git clone https://github.com/ingcreators/tesseraql
cd tesseraql
```

## One command

```bash
tesseraql serve --app examples/inventory-app --embedded-db
```

No database, no compose: an embedded PostgreSQL starts inside the process, the app's
migrations create and **seed** the schema, and every route serves immediately. Any gallery
app works the same way (`purchase-request-app`, `helpdesk-app`, `scaffold-demo-app`).

Alternatively, one container — a prebuilt image is published with each release:

```bash
docker run --rm -p 8080:8080 ghcr.io/ingcreators/tesseraql-demo:latest
```

(Building it yourself — `docker build -f deploy/Dockerfile.demo -t tesseraql-demo .` —
also works, but compiles the framework from source and takes several minutes.)

## First login

Studio signs in against the identity store, which the demo does not seed — `serve` says so at
startup and prints this step. Create an administrator once (second terminal; the CLI finds the
running embedded database by itself):

```bash
printf 'demo-password' > admin.pw
tesseraql identity-schema --app examples/inventory-app \
    --admin-login admin --admin-roles INV_READ,INV_WRITE --admin-password-file admin.pw
```

(The roles grant the demo app's own `inv.read`/`inv.write` policies, so the product pages
open for this login too. Seeding a database the app config points at elsewhere — a remote
server, say? Pass `--jdbc-url` explicitly; it takes precedence over both the config and
the running embedded database.)

## The Studio tour

Open `http://localhost:8080/_tesseraql/studio` and walk the loop the framework is built
around — every stop is live against the seeded data:

1. **Explorer** — every route, view, job and workflow the app serves, straight from the
   files on disk. Open `web/products/get.yml`: the whole page is one YAML document and one
   2-way SQL file.
2. **Data browser** — the seeded rows (the demo app ships with
   `tesseraql.studio.dataBrowser.enabled: true`). Filter, sort, export CSV; with the row
   editor enabled, fix a value under audit + confirm. The opt-in spans every declared
   datasource — switch to the `analytics` DuckDB datasource and the same browser lists
   its attached catalogs and lake tables as `catalog.schema.table` (editing stays on
   `main`).
3. **The instant loop** — edit the SQL in the source editor, apply, and the running page
   changes; scaffold a CRUD slice from a table and its pages serve immediately; create a
   migration and press **Migrate now**. No restart anywhere.
4. **Tests** — open a route and run its declarative suite in the sandbox; record a new case
   from the API console with one click.
5. **Docs portal** — the generated documentation: routes, schema, coverage, the release
   diff. `tesseraql admission --app examples/inventory-app` is the bar a shared app must
   clear ([marketplace admission](admission.md)).
6. **Dashboards** — open `http://localhost:8080/products/dashboard` in the browser: stats,
   charts and a low-stock table from a handful of SQL files and one `recipe: dashboard`
   document, no HTML anywhere in the app — including a supplier-price table read straight
   off CSV files and a price trend from the lake's snapshots, live database and analytics
   engine composing in one page. The product pages share your browser login (the demo
   app's JSON APIs stay `auth: bearer` for machine callers).
7. **The close, end to end** — on the [ops console](ops-console.md)'s jobs page
   (`/_tesseraql/ops/console/jobs`), run `pricing.loadSummary`: the ETL reads the CSV drop,
   lands the summary on the live database, and appends a lake snapshot — then the chained
   `pricing.dailyReport` writes the day's CSV and drops it in `outbox/reports/`. The
   produced file is on the **transfers** page (downloadable), the run history on
   **executions**, and the dashboard's trend chart has a new point
   ([the whole analytics loop](analytics.md)).

## The low-code loop, end to end

The demo walks the whole loop. A semi-technical author adds a column and its screen
behaviour entirely in Studio, writing no HTML and never restarting: a **migration** created
and applied with Migrate now, a **view** ([a declarative document](declarative-views.md)),
and a **recorded test** from the API console's one click.

From there the change leaves Studio. It **promotes through a release diff**
([environment profiles and promotion](promotion.md)), and the route's **latency appears on a
scraped dashboard** ([observability](deployment.md),
`deploy/grafana/tesseraql-dashboard.json`).

Each leg is held green by an integration test: the zero-restart hot reload, the Studio test
recorder, the release diff engine, and the Prometheus exposition.

## Next

- [your-first-app.md](your-first-app.md) — build one yourself, from an empty directory.
- [overview.md](overview.md) — what the framework is for, and what it is not.
- [studio.md](studio.md) — the console you just toured, in full.
- [app-layout.md](app-layout.md) — what the files you opened are, and how URLs map to them.
