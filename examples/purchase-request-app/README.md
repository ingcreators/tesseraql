# purchase-request-app

A TesseraQL starter: the smallest complete **approval workflow** — a `kind: workflow`
document drives draft → submitted → approved/rejected over one business table, with the
framework synthesizing the transition endpoints, tasks, and history (managed mode). Part of
the template gallery (roadmap Phase 47); held to the marketplace admission profile
(`tesseraql admission --app .`, docs/admission.md).

## Run it

```bash
tesseraql serve --app . --embedded-db        # zero-install: embedded PostgreSQL, auto-seeded
# or point db.main.* at your PostgreSQL and just: tesseraql serve --app .
```

Then:

- `GET /requests` — the list view (search, sort, pagination); `/requests/new` creates a draft.
- `POST /api/purchase-requests/PR-1001/submit` — the synthesized transition endpoint
  (bearer + `pr.write`); then `/approve` or `/reject` from `submitted`. A guard keeps
  zero-amount requests out; every transition lands in `tql_workflow_history`, shown on the
  detail page `/requests/PR-1001`.
- Studio (`/_tesseraql/studio`) to browse, edit, and run the declarative tests.

## What it shows

- `workflow/purchase-request.yml` — states, guarded transitions, task assignment
  (`approver.sql`), managed state (`tesseraql.workflow.mode: managed`).
- Declarative views only: `list.view.yml` (search + pagination), `new.view.yml` (form),
  `detail.view.yml` (labelled values + the history child table). No hand-written HTML.
- Deny-by-default policies defined in `config/tesseraql.yml`; CSP on every page; the
  admission profile passes.

## Layout

See [docs/app-layout.md](../../docs/app-layout.md).
