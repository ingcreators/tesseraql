# What TesseraQL is

TesseraQL builds internal business applications: the screens, APIs, batch jobs, and
integrations a company runs on. You write SQL and YAML; the framework supplies the
runtime, the transactions, identity, scheduling, file transfer, observability, and three
browser consoles.

It is aimed at teams who know their data and their business rules, and who would rather
spend their effort there than on a front-end build pipeline.

## The shape of an application

An application is a directory you own, in your own repository:

```
web/orders/get.yml          the route:  GET /orders
web/orders/search.sql       its SQL, runnable as-is in any SQL client
web/orders/list.view.yml    the page, declared rather than written
db/migration/V1__orders.sql your schema, in plain SQL
tests/orders.yml            the suite, declared alongside
```

There is no generated code to regenerate, no ORM mapping to keep in step, and no
JavaScript build. The SQL file you paste into your database client is the same file
production runs.

## What you get without building it

| | |
| --- | --- |
| **Identity** | Local accounts, SAML SSO, SCIM provisioning, roles, policies, sessions |
| **Data protection** | Deny-by-default routes, row-level scoping, multi-tenancy, field masking |
| **Writes** | Multi-statement transactions, declarative validation, optimistic locking, idempotent replay |
| **Work** | Scheduled jobs, business-day calendars, chunked batch, polled file sources |
| **Integration** | Managed HTTP and file connectors, messaging channels, a transactional outbox, webhooks |
| **Output** | JSON, server-rendered HTML, CSV, Excel, PDF, HTML email |
| **Evidence** | Declarative test suites with SQL coverage, an audit trail, Prometheus metrics, a generated per-app reference |
| **Consoles** | [Studio](studio.md) to author, [operations](ops-console.md) to watch, [IAM Admin](iam-admin.md) for people |

## What it is not

Being honest about this saves everyone time.

- **Not a general web framework.** It is shaped for business applications over a
  relational database. A public marketing site or a real-time game is the wrong fit.
- **Not a single-page-application backend.** Responses are server-rendered HTML with
  htmx, or JSON. If your front end is React and you want it to stay that way, you would
  use half the framework.
- **Not a low-code platform with a proprietary store.** Everything is files in your
  repository, reviewed and deployed like any other code.
- **Not schema-managed.** You write the migrations. The framework manages only its own
  `tql_*` tables.

## Databases

PostgreSQL, MySQL, Oracle, and SQL Server, with dialect-aware SQL resolution and
per-dialect migrations. PostgreSQL needs no extra setup; the other drivers are opt-in
because their licences differ ([getting-started.md](getting-started.md)).

An embedded PostgreSQL is available for development, so a first run needs no database at
all.

## Where to start

- **See it first** — [the five-minute demo](five-minute-demo.md) boots a seeded
  application with Studio open, in one command.
- **Build something** — [getting started](getting-started.md) installs the CLI, then
  [your first app](your-first-app.md) walks from an empty directory to a tested feature.
- **Understand the model** — [concepts](concepts.md) explains documents, recipes, and how
  a request travels.

## Next

- [five-minute-demo.md](five-minute-demo.md) — a running application in one command.
- [concepts.md](concepts.md) — the mental model.
- [getting-started.md](getting-started.md) — install and scaffold.
