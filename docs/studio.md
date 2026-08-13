# Studio

Studio is a browser IDE for the application that is currently running. It reads the same
files on disk that the framework serves, edits them as drafts, and applies a change to the
live process without a restart. It is the fastest loop the framework has, and it is where
a semi-technical author can do real work without a checkout.

Open it at `/_tesseraql/studio/ui`. `tesseraql serve` prints the URL at startup.

## The editing model

Studio never writes straight to your source. Three steps stand between an edit and the
running application:

1. **Draft.** An edit is saved under `work/studio/drafts`, leaving the source untouched.
   Drafts survive restarts and are listed on the Drafts page.
2. **Preview.** A draft compiles without being applied, so a mistake is reported before it
   reaches the route. The compare panel shows the diff against the current source.
3. **Apply.** Applying writes the source and hot-reloads what changed: a route edit bounces
   that route, a workflow edit rebuilds its transition endpoints, and a shared-definition
   edit rebuilds every route that bakes it in.

If the source changed underneath a draft, applying is refused until you review the
conflict. With `tesseraql.studio.confirmApply` set, every apply requires that review, not
just conflicting ones — on every surface: the editor confirms in the compare panel, and an
automation calling the JSON API passes `confirm=true` (or `force=true`, which also
acknowledges a conflict).

Jobs, queue consumers, and `config/` changes still need a restart. Everything else is
instant.

## Read-only by default in production

Studio is mounted in every application, but its writes are gated:

| Key | Effect |
| --- | --- |
| `tesseraql.studio.enabled` | Mounts Studio at all. Default `true`. |
| `tesseraql.studio.readOnly` | The master switch. Read-only Studio refuses every write. |
| `tesseraql.studio.editRoles` | An allow-list. When set, only callers holding one of these roles may edit. |
| `tesseraql.studio.dataBrowser.enabled` | Opt-in. Off by default. |
| `tesseraql.studio.dataBrowser.edit.enabled` | Row editing inside the data browser. |
| `tesseraql.studio.scaffold.enabled` | The scaffolding screens. |
| `tesseraql.studio.testRunner.enabled` | Running suites from the browser. |
| `tesseraql.studio.confirmApply` | Require a reviewed diff before every apply. |

The read-only switch is enforced twice: once in the runtime against the caller's roles, and
again in the service layer. A production deployment can leave Studio mounted for its
documentation and health screens with editing off.

## Explorer

The landing screen lists every route, view, job, and workflow the application serves,
straight from the files on disk. Open one and you see the whole thing: the YAML document,
its 2-way SQL, and its template, each editable in the source editor.

This is the fastest way to answer "what does this screen actually do", because the answer
is one document and one SQL file.

## Documentation

Studio serves the application's own generated reference. It is the same material the
[documentation portal](documentation-portal.md) publishes, live against the running app.

- **Overview** and **Schema** — every route, and every table and column the database holds.
- **Domains**, **Rules**, **Decisions** — the shared definitions the application declares.
- **Coverage** — SQL line and branch coverage from the declarative suites.
- **Release diff** — what changed since the last captured baseline.
- **Export** — the same reference as OpenAPI, the htmx contract, or PDF.

## Authoring

The authoring screens generate ordinary TesseraQL source. Nothing they emit is special:
the output is YAML, SQL, and Thymeleaf you can read, review, and hand-edit afterwards.

| Screen | Produces |
| --- | --- |
| Scaffold | A CRUD slice from a table: routes, SQL, pages, and a suite ([scaffolding.md](scaffolding.md)) |
| Pages / Builder | Declarative views, and a visual canvas over ejected templates ([declarative-views.md](declarative-views.md)) |
| SQL builder | 2-way SQL from a table and its columns ([two-way-sql.md](two-way-sql.md)) |
| Validation | Validation rules ([declarative-validation.md](declarative-validation.md)) |
| Decide builder / Decision rows | Decision tables and their rows |
| Migration | A migration file, applied with **Migrate now** |
| Mail | HTML email templates, with a live preview and a test send |
| Menu | The application's role-filtered navigation |
| Wizards | Multi-step forms |
| Messages | Message catalogs, edited live ([internationalization.md](internationalization.md)) |
| API console | Requests against the running app; a response can be recorded as a test case ([testing.md](testing.md)) |
| Copilot | A chat panel that drives these same screens as tools ([copilot.md](copilot.md)) |

## Governance

The screens an operator or reviewer uses:

- **Drafts** — every unapplied edit, appliable or discardable in bulk.
- **Audit** — who changed what through Studio.
- **Health** — the same probes the ops console reports.
- **Security** — the effective policy for every route, and live policy editing.
- **Config** — the resolved configuration, with a curated set of editable keys.
- **Data** — the data browser: filter, sort, export CSV, and edit rows under audit and
  confirmation. It spans every declared datasource, including
  [DuckDB](duckdb.md) catalogs.
- **Flags** — feature flags, toggled live.
- **Connectors** — credentials, egress rules, and webhooks
  ([connectors.md](connectors.md)).

## Studio or your own editor

Both loops are the same loop, and you can move between them freely.

`tesseraql serve --app . --watch` watches the `web/` tree, `workflow/`, and the shared
definitions, and hot-reloads on save. That is exactly what Studio's **Apply** does. Use
Studio when you want the generated screens, the data browser, and the documentation beside
the edit; use your editor with `--watch` when you want git, diffs, and your own keymap.

The [VS Code extension](vscode-extension.md) adds linting, the CLI verbs, and a project
tree to the editor path.

## What Studio does not do

- **It is not the operations console.** Live system state — job runs, traces, transfers,
  the outbox — is [the ops console](ops-console.md).
- **It is not IAM Admin.** Users, sessions, and delegations are
  [IAM Admin](iam-admin.md).
- **It does not deploy.** An applied change is a file on disk. Getting it to production is
  git, CI, and a release ([promotion.md](promotion.md)).

## Next

- [your-first-app.md](your-first-app.md) — build something with Studio open beside you.
- [scaffolding.md](scaffolding.md) — what the generated CRUD slice contains.
- [promotion.md](promotion.md) — how a Studio edit reaches production.
