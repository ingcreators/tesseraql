# Concepts

An application is a directory of documents. This page is the mental model behind them:
what the pieces are, how a request travels through them, and which piece to reach for.
Read it once, and the rest of the documentation stops being a list of features.

## Three materials

Everything you author is one of three things.

**2-way SQL** carries the data access. Each file is a complete statement you can paste
into a SQL client and run, *and* a template the framework binds at runtime. The binds and
branches live in SQL comments, so nothing is generated and nothing is hidden
([two-way-sql.md](two-way-sql.md)).

**YAML documents** carry the declarations: what a route accepts, what it validates, who
may call it, what it answers with, when a job runs. They are the reviewable surface — a
diff on a YAML document tells you what changed about the behaviour.

**Templates** carry the markup, when you need markup at all. Many pages are declared
rather than written ([declarative-views.md](declarative-views.md)); templates are the
escape hatch when a page outgrows the declaration.

The framework supplies everything around these: the runtime, transactions, identity,
scheduling, file transfer, observability, and the consoles.

## Documents and kinds

Every YAML document declares its `kind`.

| Kind | What it declares | Lives under |
| --- | --- | --- |
| `route` | An HTTP endpoint | `web/` |
| `job` | Scheduled or polled work | `batch/` |
| `view` | A page, declaratively | beside its route |
| `workflow` | A business document's states and transitions | `workflow/` |
| `scope` | A data-scope rule | `scope/` |
| `attachment` | Files bound to a record, with its upload and download routes | with the record's routes |
| `tool`, `resource`, `ui`, `prompt` | An MCP surface for agents | `mcp/` |

A route's `recipe` says what shape of endpoint it is: `query-json` and `command-json` for
JSON APIs, `query-html` and `page` for HTML, `query-export` / `file-import` /
`file-export` for files, `webhook` for inbound calls, `queue-consume` for messages, and
`batch-pipeline` for jobs. The recipe decides which keys the document
may carry, which is why the reference is organized by it.

## How a request travels

A request meets the same stages in the same order, whatever the recipe:

1. **Routing.** The URL maps to a directory. `web/users/{id}/get.yml` serves
   `GET /users/{id}`; there is no route table to keep in step
   ([app-layout.md](app-layout.md)).
2. **Authentication and authorization.** The route declares how it authenticates and
   which policy it requires. A route that declares nothing is unreachable — deny by
   default ([authentication.md](authentication.md)).
3. **Input binding.** Declared fields are coerced to their types and refused when they do
   not fit. Undeclared fields do not reach your SQL.
4. **Validation.** Cross-field rules and validation SQL run before anything is written
   ([declarative-validation.md](declarative-validation.md)).
5. **Execution.** One statement, or several as one transaction
   ([transactional-writes.md](transactional-writes.md)).
6. **Response.** The same result becomes JSON or HTML depending on what the caller asked
   for ([response-shaping.md](response-shaping.md)).
7. **After commit.** Notifications and events ride the transactional outbox, so they are
   sent if and only if the write succeeded ([notifications.md](notifications.md)).

## Shared definitions

Some knowledge belongs to the application, not to one route. It is declared once and
referenced.

| Directory | Holds | Referenced by |
| --- | --- | --- |
| `domains/` | Field knowledge: type, bounds, classification, widget | `domain:` on an input field |
| `rules/` | Validation rule sets | `use:` inside `validate:` |
| `decisions/` | Decision tables | `decide:` |
| `scope/` | Data-scope rules | route security |
| `calendars/` | Business-day calendars | a job's `calendar:` |
| `messages/` | Message catalogs per locale | everything user-visible |

Changing a shared definition changes every route that references it, which is the point.
It is also why a shared-definition edit rebuilds every route rather than one.

## The database is yours

TesseraQL does not own your schema. Tables are created by migrations you write, in SQL,
under `db/migration` ([app-layout.md](app-layout.md)). Column names are used verbatim as
input names, binds, and path parameters — the name in the DDL is the name everywhere
([identifiers.md](identifiers.md)).

The framework's own tables are prefixed `tql_`, and they are the only ones it manages.

## What runs it

An application is served by the `tesseraql` CLI in development and packaged as a
container in production. The same engine backs both, and the same engine backs the Maven
goals CI runs.

Three consoles ship with every application: [Studio](studio.md) for authoring,
[the operations console](ops-console.md) for watching, and [IAM Admin](iam-admin.md) for
people.

## Next

- [app-layout.md](app-layout.md) — the directory these documents live in.
- [two-way-sql.md](two-way-sql.md) — the SQL contract in detail.
- [your-first-app.md](your-first-app.md) — build one.
- [glossary.md](glossary.md) — the terms, one line each.
