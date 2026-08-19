# Glossary

Terms this documentation uses in a specific sense. Where a term has a page, the page is the
full account and this is the one-line version.

**2-way SQL** — a SQL file that is both a runnable statement and a parameterized template.
Binds and conditional blocks live in SQL comments, so the file runs unchanged in any SQL
client. See [two-way-sql.md](two-way-sql.md).

**acting role** — the one application role a caller is currently acting as, chosen per
browser tab (the `/_as/<role>/` address segment) or per token (`tesseraql token --as`).
Policies, scopes, menus, and field policies read the acting view; the `tql.app.use` fence
and every framework atom check read the union. See
[authentication.md](authentication.md#acting-roles-activation).

**activation** — selecting an acting role at use time. Narrows, never widens: the active
view is built from the caller's own grants, so a forged signal can only select among held
roles. See [authentication.md](authentication.md#acting-roles-activation).

**admission (route)** — the concurrency limit, rate limit, and execution lane a route
declares. Not to be confused with the admission profile.

**admission profile** — the machine-checkable bar an application must clear before someone
else installs it. See [admission.md](admission.md).

**app / application** — a directory of YAML documents, SQL files, templates, and migrations
that the framework serves. Yours, in your repository.

**application role** — a role scoped to one application (`tql_roles.application`), such as
an approver role in `orders`. A user holding several activates one at a time per tab.
Stack-wide roles (no application) are always active. See [iam-admin.md](iam-admin.md).

**bindable path** — a reference to a value in the execution context, such as `params.id`,
`rows`, or `steps.insert.keys.id`. Used wherever a declaration needs a value rather than a
literal.

**bundled app** — an application the framework ships and mounts itself: Studio, the ops
console, IAM Admin. Built from the same materials as yours.

**business date** — the date a scheduled run is *for*, which is not always the date it ran
on. See [jobs.md](jobs.md).

**command** — a route that writes. `command-json` executes one business operation, possibly
several statements, in one transaction. See [transactional-writes.md](transactional-writes.md).

**decision table** — a shared table of conditions to outputs, evaluated before validation and
published for SQL and directives to read.

**domain (field)** — named field knowledge — type, bounds, classification, widget — declared
once under `domains/` and referenced with `domain:`.

**execution lane** — a named pool a route or job runs on, so slow work cannot starve fast
work.

**IAM Admin** — the bundled console for users, sessions, and delegations. See
[iam-admin.md](iam-admin.md).

**idempotency key** — a caller-supplied key that makes a replayed command return its stored
response instead of writing twice.

**outbox** — the transactional store notifications and events are recorded in, so they are
delivered if and only if the write committed.

**ops console** — the bundled console for live system state. See
[ops-console.md](ops-console.md).

**policy** — a named authorization rule a route requires. Evaluated against the resolved
principal. See [authentication.md](authentication.md).

**principal** — the authenticated caller: their subject, roles, permissions, claims, and
tenant.

**recipe** — what shape of endpoint a route is: `query-json`, `command-json`, `query-html`,
`page`, `query-export`, `file-import`, `file-export`, `webhook`, `queue-consume`, or the batch
recipes. The recipe decides which keys the document may carry.

**route** — a `kind: route` document under `web/`, serving one URL and method. The directory
path is the URL path.

**scope (data)** — a rule confining which rows a request may see, derived from the principal
rather than written into each query. See [data-scoping.md](data-scoping.md).

**shared definition** — knowledge declared once for the whole application and referenced:
domains, rules, decisions, scopes, calendars.

**source** — one named acquisition of rows, declared under `sources:`. Its arm names the
mechanism — `sql:`, `http:`, `contract:` or `service:` — and every source publishes the same
envelope under its own name: `<name>.rows`, `.rowCount`, `.first`. See
[response-shaping.md](response-shaping.md).

**`main`** — the reserved source name every default resolves to: a view's rows, an export's
extraction, what pagination pages. A naming convention rather than a slot, so a document with no
use for one simply does not declare it.

**Studio** — the bundled browser IDE. See [studio.md](studio.md).

**suite** — a declarative test file under `tests/`, exercising routes, SQL, security, or
contracts. See [testing.md](testing.md).

**tenant** — a customer organization in a multi-tenant deployment, isolated from every other.
See [multi-tenancy.md](multi-tenancy.md).

**view** — a `kind: view` document describing a page — list, form, detail, or dashboard —
instead of writing its markup. See [declarative-views.md](declarative-views.md).

**workflow** — a `kind: workflow` document giving a business document its states and the
transitions between them. See [approval-workflow.md](approval-workflow.md).

**`.tqlapp`** — a packaged, hash-pinned application archive, installable into a runtime.

**`tql_*` tables** — the framework's own tables. Everything else in your database is yours.

## Next

- [concepts.md](concepts.md) — how these fit together.
- [reference-yaml-surface.md](reference-yaml-surface.md) — every key, generated from the schema.
