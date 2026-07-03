# inventory-app

A TesseraQL starter: **inventory management** with declarative views end to end — a
searchable, sortable, paginated product list, a dashboard (stats, a bar chart, a low-stock
table), forms, and a stock adjustment guarded by a **declarative validation rule** (stock
never goes negative; a field-scoped 422 before anything writes). No hand-written HTML. Part
of the template gallery (roadmap Phase 47); held to the marketplace admission profile
(`tesseraql admission --app .`).

## Run it

```bash
tesseraql serve --app . --embedded-db     # embedded PostgreSQL, auto-seeded catalog
```

- `/products` — list (search, sort, pagination) · `/products/dashboard` — the dashboard ·
  `/products/new` — create.
- `POST /products/adjust` `{sku, delta}` — the validated stock adjustment (bearer +
  `inv.write`).
- Studio at `/_tesseraql/studio`; run the suite in `tests/inventory-test.yml` from the editor.

## Layout

See [docs/app-layout.md](../../docs/app-layout.md).
