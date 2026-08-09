# Frequently asked questions

Questions that come up when deciding whether to use TesseraQL, and while getting used to it.
For "it is broken", see [troubleshooting.md](troubleshooting.md).

## Choosing it

### Is this a low-code platform?

No, in the sense that matters: there is no proprietary store and no exported artefact. An
application is files in your repository — YAML, SQL, and templates — reviewed in pull
requests and deployed like any other code.

It *is* low-code in the sense that a semi-technical author can add a column, a screen, and a
test in [Studio](studio.md) without writing HTML or restarting anything. Those edits land as
ordinary files that a developer then reviews.

### Do I have to give up my existing database?

No. The framework manages only its own `tql_*` tables. Your schema stays yours, migrations
are SQL you write, and column names are used verbatim — there is no mapping layer to
configure. Putting an API over a database you did not create is a documented path
([guide-existing-database.md](guide-existing-database.md)).

### Can I use React (or any front end) with it?

You can. A JSON API is a `query-json` or `command-json` route, and it can be backed by the
same 2-way SQL file as an HTML page — the SQL is written once and referenced from both
documents. So a separate front end is a supported shape.

But you would be using about half the framework and paying for the other half: the views,
the UI kit, the page builder, and the hypermedia patterns all go unused. If your front end
is settled and staying, weigh that early — [overview.md](overview.md) is explicit about
where TesseraQL is the wrong fit.

### Which databases are supported?

PostgreSQL, MySQL, Oracle, and SQL Server, with dialect-aware SQL resolution and per-dialect
migrations. PostgreSQL needs no extra setup. The other three drivers are opt-in because their
licences differ, and the framework never redistributes them
([getting-started.md](getting-started.md#opt-in-modules-drivers-and-codecs)).

### Do I need Docker, Node, or Maven?

None of them are required. An embedded PostgreSQL means a first run needs no database at all;
the UI is served from a WebJar so there is no JavaScript build; and the CLI loop needs no
Maven. Maven is there when you want CI to run the same engine
([getting-started.md](getting-started.md)).

### Is it stable enough to build on?

It is pre-1.0, and backward compatibility is not yet a goal — replaced designs are deleted
rather than shimmed, and breaking changes are recorded in the CHANGELOG. If you need a frozen
contract today, that is worth weighing. For evaluating the framework's own posture, see
[the security self-assessment](security-hardening.md) and [threat model](threat-model.md).

## Writing applications

### Why is my SQL in comments?

So the file stays runnable. A 2-way SQL file is a complete statement you can paste into any
SQL client, *and* the template the framework binds at runtime — the binds and branches live
in comments precisely so both are true at once ([two-way-sql.md](two-way-sql.md)). What you
test in your editor is what production runs.

### Do I have to write templates?

Usually not. A page can be a `kind: view` document — list, form, detail, or dashboard —
rendered by the framework ([declarative-views.md](declarative-views.md)). Templates are the
escape hatch when a page outgrows the declaration, and there is a documented ladder from one
to the other.

### Where does business logic go?

In SQL and in declarations. Validation is declarative
([declarative-validation.md](declarative-validation.md)), policy that changes without a
release goes in decision tables, and multi-statement operations are declared steps in one
transaction ([transactional-writes.md](transactional-writes.md)). The default assumption is
that an application needs no Java of its own.

### Can I call Java from a route?

A route can name a runtime `service:` provider the framework supplies. Beyond that, an
application may ship signed runtime-extension jars under `plugins/`, each with a detached
Ed25519 signature verified against `tesseraql.plugins.trustedKeys` and loaded in an isolated
class loader ([app-layout.md](app-layout.md)).

Reach for that last. Wanting custom Java is usually a sign the declaration you need already
exists — check the [YAML surface](reference-yaml-surface.md) first.

### Why does my column name appear unchanged in the URL?

Because that is the rule: the identifier in your DDL is the identifier everywhere — input
name, SQL bind, path parameter, template field. No camel-case conversion happens, including
for non-ASCII names ([identifiers.md](identifiers.md)). It means legacy naming costs you
nothing.

## Operating it

### Should Studio be enabled in production?

Mounted, usually yes; writable, usually no. `tesseraql.studio.readOnly: true` keeps the
documentation, health, and security screens available while refusing every write, and
`tesseraql.studio.editRoles` narrows editing to named roles where you do want it
([studio.md](studio.md#read-only-by-default-in-production)).

### How does an edit made in Studio reach production?

Through git. An applied edit is a file on disk; from there it is a branch, a pull request, CI,
and a release, exactly like any other change ([promotion.md](promotion.md)). Studio's audit
trail records who changed what.

### How do I know a nightly job actually ran?

Every finished run counts on the Prometheus exposition, labelled by job, app, and status, so
"did tonight's close run" is one query. The [ops console](ops-console.md) shows recent
executions, and a job can declare an SLA that pages someone when it does not
([jobs.md](jobs.md)).

### Can one deployment serve several customers?

Yes — [multi-tenancy.md](multi-tenancy.md) covers tenant isolation, and
[data-scoping.md](data-scoping.md) the row-level confinement within a tenant. Tenancy is off
until you declare it.

### How do I upgrade?

Two things carry a version: the CLI you installed, and the framework version your application
builds against. [upgrading.md](upgrading.md) covers moving both, and the release diff shows
what changed between two versions of your own application.

## Next

- [troubleshooting.md](troubleshooting.md) — symptoms, causes, and fixes.
- [overview.md](overview.md) — what the framework is, and what it is not.
- [concepts.md](concepts.md) — the model behind the answers above.
