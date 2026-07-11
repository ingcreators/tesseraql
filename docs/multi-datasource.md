# Multi-datasource routes

TesseraQL applications can run route SQL on named datasources other than `main`
— a reporting warehouse, a legacy system, a read replica with its own schema. One
key, `datasource:`, selects the datasource; consistency across databases is handled
by a deliberately narrow model built on the existing messaging machinery.

## Declaring a datasource

Every block under `tesseraql.datasources` builds its own connection pool, is made
available to routes by name, and migrates from its own
`db/<name>/migration[-<vendor>]` tree under its own schema-history table (an unknown
name fails fast with `TQL-APP-4201`):

```yaml
# config/tesseraql.yml
tesseraql:
  datasources:
    main:                        # required — the application database
      jdbcUrl: ${db.main.url}
      username: ${db.main.username}
      password: ${db.main.password}
    reporting:                   # a named datasource routes may opt into
      jdbcUrl: jdbc:mysql://warehouse:3306/reports
      username: reports
      password: ${secret.env.REPORTING_DB_PASSWORD}
```

`jdbcUrl` is required; `username` and `password` are optional, and a password belongs
in a secret reference, never a literal. A block may also declare an explicit
`dialect:` (otherwise inferred from its `jdbcUrl`) and the per-pool tuning knobs
listed in [deployment](deployment.md). By default the compiler pins every SQL
execution — reads and the [transactional command engine](transactional-writes.md)
alike — to `main`; `datasource:` is how a route opts out.

## Reading from a named datasource

A route declares the datasource its SQL runs on; read recipes accept it wholesale:

```yaml
version: tesseraql/v1
id: sales.summary
kind: route
recipe: query-json
datasource: reporting          # a name under tesseraql.datasources
sql:
  file: sales-summary.sql
  mode: query
```

A page composing several result sets may pick per query — the route-level value is
the default, a **read-only** named query may override it:

```yaml
recipe: page
sql: { file: orders-open.sql, mode: query }        # runs on main
queries:
  turnover: { file: turnover.sql, mode: query, datasource: reporting }
```

- **Dialect follows the datasource.** The compiler resolves each datasource's dialect
  exactly as it does `main`'s (`tesseraql.datasources.<name>.dialect`, else inferred
  from its `jdbcUrl`) and bakes it into that route's endpoints — pagination clauses,
  streaming profiles, and label normalization are the target database's, so a MySQL
  `reporting` beside a PostgreSQL `main` paginates correctly.
- **Tenancy routes `main` only.** Per-tenant datasource resolution replaces the
  *main* datasource for the tenant's exchange; an explicit non-main `datasource:` is
  authoritative and is never overridden by tenant routing. (Named datasources are
  deployment-shared infrastructure — a reporting warehouse — not tenant homes.)
- **No cross-datasource SQL.** A single statement runs on a single connection; a page
  *composes* result sets from several datasources, SQL never joins across them.

Lint keeps a typo from becoming a runtime surprise: a `datasource:` naming a
datasource that is not declared is `TQL-YAML-1035` (checked against the same config
lint already reads for channels), and a per-step `datasource:` inside a command's
transactional pipeline — which would silently split the transaction — is refused
outright as `TQL-YAML-1037`.

## The transaction stance

- **One business operation is one local transaction on one datasource — never two.**
  The [transactional command engine](transactional-writes.md)'s whole guarantee is a
  single connection carrying every step, validation, and outbox insert to one commit.
  A `datasource:` moves that connection to a named datasource; it never splits it.
- **No JTA/XA.** Two-phase commit is a standing operational tax on every deployment —
  XA drivers and XA-capable pooling, a recovery log, heuristic-outcome monitoring —
  against the framework's JDK-only, no-heavy-runtime grain. Instead,
  **cross-database consistency is eventual, explicit, and rides machinery that
  already exists** (the [transactional outbox](notifications.md) →
  [messaging channels](messaging.md) → `queue-consume`).
- **Framework bookkeeping lives on `main`.** The outbox, the durable `tql_event` log
  and its dedup records, workflow state, sequences, sessions, preferences — all of it
  stays on the main datasource. A route on another datasource is *plain SQL*: what it
  reads and writes there is entirely the app's schema.

## Writing across databases: the projection pattern

`datasource:` is equally legal on the transactional recipes (`command-json`,
`webhook`, `queue-consume`, MCP tools): the *whole* command transaction — steps,
declarative validation, sequence-free plain SQL — runs on the named datasource, with
the same commit-all-or-roll-back-all contract, now against that database.

The blessed shape for "a write on `main` must reach the second database" is a
**projection**: the command publishes, a consumer applies —

```yaml
# web/orders/create.yml — the business command, unchanged, on main
publish:
  channel: events
  topic: orders.created
  key: body.orderId
  payload: { orderId: body.orderId, total: body.total }
```

```yaml
# consume/orders/project-reporting.yml — the projection
recipe: queue-consume
datasource: reporting
consume:
  channel: events
  topic: orders.created
  idempotencyKey: body.orderId
input:
  orderId: { type: string, required: true }
  total:   { type: number }
sql:
  file: upsert-order-projection.sql   # an idempotent upsert, in reporting's schema
  mode: update
```

Delivery semantics are [messaging](messaging.md)'s, unchanged, because the *bus*
never moves: the event is written in the main command's transaction (a rolled-back
command never publishes), relayed onto the durable `tql_event` log on `main`,
claimed with `SKIP LOCKED` on `main`, deduplicated against `main`'s consumed-key
records. Only the consumer's **apply transaction** runs on `reporting`. The apply
commit and the consumed-mark are two transactions on two databases, so the honest
contract is the one the messaging documentation already states: **at-least-once
delivery, effectively exactly-once per idempotency key, and the projection SQL is
an idempotent upsert** — a crash between apply and acknowledge redelivers, the key
check skips it, and the upsert makes even the residual window harmless.

What a non-main transaction cannot carry is anything whose tables live on `main`:
`notify:`, `publish:`, `outbox:`, workflow transitions, `sequence:` allocation.
Declaring one is `TQL-YAML-1036` at build time — not a runtime surprise on a
datasource that lacks `tql_outbox_event`. (A projection that must fan out further
does it on `main`: consume on `main`, publish again, project in a second consumer.)
Declarative `validate:` rules run on the route's datasource by design — they are
checks against the state being written.

## Lint and error surface

| Code            | Severity | Meaning                                                                 |
| --------------- | -------- | ----------------------------------------------------------------------- |
| `TQL-YAML-1035` | error    | `datasource:` names a datasource not declared under `tesseraql.datasources` |
| `TQL-YAML-1036` | error    | a non-main route declares a main-anchored feature (`notify:`, `publish:`, `outbox:`, workflow, `sequence:`) |
| `TQL-YAML-1037` | error    | a per-step `datasource:` inside a transactional pipeline (`steps:`)     |
| `TQL-SQL-2502`  | runtime  | the named datasource is not bound at execution time (existing)          |
| `TQL-APP-4201`  | runtime  | a `db/<name>/migration` tree names an undeclared datasource (existing)  |

The lint rules are backed by a compile-time guard (`TQL-CAMEL-3112`), so a
hot-reloaded or hand-mounted route that skipped lint still cannot carry a
main-anchored feature into a non-main transaction.

## Deliberately out of scope (documented, not implied)

- **JTA/XA and any two-datasource transaction** — the stance above is the design.
- **Cross-datasource joins or subqueries** — compose result sets, don't merge SQL.
- **Broker transports** (Kafka/JMS) — not currently supported; planned as opt-in
  leaf modules.
- **Studio's data browser and the docs portal's schema introspection** stay on
  `main` for now; extending them per-datasource is planned, not currently supported.
- **Per-tenant named datasources** — tenancy remains a `main`-only concern.
