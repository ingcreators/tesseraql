# The five-minute demo

One command boots a complete, seeded, browsable app with Studio open.

## One command

```bash
tesseraql serve --app examples/inventory-app --embedded-db
```

No database, no compose: an embedded PostgreSQL starts inside the process, the app's
migrations create and **seed** the schema, and every route serves immediately. Any gallery
app works the same way (`purchase-request-app`, `helpdesk-app`, `scaffold-demo-app`).

Or as one container:

```bash
docker build -f deploy/Dockerfile.demo -t tesseraql-demo .
docker run --rm -p 8080:8080 tesseraql-demo
```

## The Studio tour

Open `http://localhost:8080/_tesseraql/studio` and walk the loop the framework is built
around — every stop is live against the seeded data:

1. **Explorer** — every route, view, job and workflow the app serves, straight from the
   files on disk. Open `web/products/get.yml`: the whole page is one YAML document and one
   2-way SQL file.
2. **Data browser** — the seeded rows (opt-in via `tesseraql.studio.dataBrowser.enabled`).
   Filter, sort, export CSV; with the row editor enabled, fix a value under audit + confirm.
3. **The instant loop** — edit the SQL in the source editor, apply, and the running page
   changes; scaffold a CRUD slice from a table and its pages serve immediately; create a
   migration and press **Migrate now**. No restart anywhere.
4. **Tests** — open a route and run its declarative suite in the sandbox; record a new case
   from the API console with one click.
5. **Docs portal** — the generated documentation: routes, schema, coverage, the release
   diff. `tesseraql admission --app .` is the bar a shared app must clear
   ([marketplace admission](admission.md)).
6. **Dashboards** — `/products/dashboard` renders stats, a chart and a low-stock table from
   three SQL files and one `view: dashboard` document. No HTML anywhere in the app.

## The low-code loop, end to end

The demo walks the whole loop: a semi-technical author adds a column and its screen
behavior entirely in Studio — **migration** (created and applied with Migrate now, via the
hot reload), **view** (a [declarative document](declarative-views.md)), **recorded test**
(the API console's one click, in Studio) — writing no HTML and never restarting; the change
**promotes through a release diff** ([environment profiles and promotion](promotion.md));
and the route's **latency shows up on a scraped dashboard**
([observability](deployment.md), `deploy/grafana/tesseraql-dashboard.json`). Each leg is
held green by an integration test: the zero-restart hot reload, the Studio test recorder,
the release diff engine, and the Prometheus exposition.
