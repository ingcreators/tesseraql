# TesseraQL example apps

Each example is a self-contained, runnable TesseraQL application that **owns its schema** via
`db/migration` (the standard app layout — see [../docs/app-layout.md](../docs/app-layout.md)). Copy
one as a starting point, or generate a fresh skeleton with `tesseraql new`. Running `serve` against
an empty PostgreSQL applies the app's migrations on start, so there is no manual schema setup.

| App | What it shows |
| --- | --- |
| [`scaffold-demo-app`](scaffold-demo-app) | The smallest realistic app: the output of `tesseraql new` + `tesseraql scaffold crud` — a CRUD resource with 2-way SQL, a hypermedia page, the Phase 18 write conventions (identity key, version column, audit columns), and a smoke suite. Start here when learning the layout. |
| [`user-admin-app`](user-admin-app) | The feature tour: bearer/API-key/browser auth, authorization policies, i18n, batch jobs, notifications, managed identity, printable PDFs, and the developer MCP tools. The repository's integration tests also mount this app, so it doubles as a living, always-green reference. |

Both apps follow the same conventions, so what you learn from one transfers to the other. See the
top-level [README](../README.md) for how to run `user-admin-app`, and
[../docs/app-layout.md](../docs/app-layout.md) for the full directory contract.
