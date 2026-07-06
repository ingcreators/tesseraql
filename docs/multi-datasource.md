# Multi-datasource routes — reads across connectors, consistency through projections

Status: design accepted 2026-07-06 (roadmap Phase 53, Horizon 9). **All three slices
are delivered — Phase 53 is complete and milestone M18 is met** on two real
PostgreSQL databases (`MultiDatasourceReadIntegrationTest`,
`MultiDatasourceProjectionIntegrationTest`). One addition from implementation: the
lint rules are backed by a compile-time guard (`TQL-CAMEL-3112`), so a hot-reloaded
or hand-mounted route that skipped lint still cannot carry a main-anchored feature
into a non-main transaction.

The runtime has carried multiple database connectors since Phase 18: every
`tesseraql.datasources.<name>` block builds its own HikariCP pool, is bound by name in
the Camel registry, and migrates from its own `db/<name>/migration[-<vendor>]` tree
under its own Flyway history table (an unknown name fails fast with `TQL-APP-4201`).
What no route could do is *use* one: the compiler pins every SQL execution — reads and
the transactional command engine alike — to `main`. This phase opens that last door
with one new word, `datasource:`, and one deliberately narrow consistency model.

## The transaction stance first

- **One business operation is one local transaction on one connector — never two.**
  The Phase 18 engine's whole guarantee is a single JDBC connection carrying every
  step, validation, and outbox insert to one commit. A `datasource:` moves that
  connection to a named connector; it never splits it.
- **No JTA/XA.** Two-phase commit would demand XA drivers and XA-capable pooling, a
  recovery log with crash-recovery operations, `max_prepared_transactions` tuning on
  every PostgreSQL, and heuristic-outcome monitoring — a standing operational tax on
  every deployment, against the project's JDK-only, no-heavy-runtime grain
  (extension principle 5). TesseraQL takes the transactional-outbox answer instead:
  **cross-database consistency is eventual, explicit, and rides machinery that
  already exists** (Phase 20 outbox → Phase 27 channels → `queue-consume`).
- **Framework bookkeeping lives on `main`.** The outbox, the durable `tql_event` log
  and its dedup records, workflow state, sequences, sessions, preferences — all of it
  stays on the main connector. A route on another connector is *plain SQL*: what it
  reads and writes there is entirely the app's schema.

## Reading from a named connector (slice 2)

A route declares the connector its SQL runs on; read recipes accept it wholesale:

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

- **Dialect follows the connector.** The compiler resolves each connector's dialect
  exactly as it does `main`'s (`tesseraql.datasources.<name>.dialect`, else inferred
  from its `jdbcUrl`) and bakes it into that route's endpoints — pagination clauses,
  streaming profiles, and label normalization are the target database's, so a MySQL
  `reporting` beside a PostgreSQL `main` paginates correctly.
- **Tenancy routes `main` only.** Per-tenant datasource resolution replaces the
  *main* connector for the tenant's exchange; an explicit non-main `datasource:` is
  authoritative and is never overridden by tenant routing. (Named connectors are
  deployment-shared infrastructure — a reporting warehouse — not tenant homes.)
- **No cross-connector SQL.** A single statement runs on a single connection; a page
  *composes* result sets from several connectors, SQL never joins across them.

Lint keeps a typo from becoming a runtime surprise: a `datasource:` naming a
connector that is not declared is `TQL-YAML-1035` (checked against the same config
lint already reads for channels), and a per-step `datasource:` inside a command's
transactional pipeline — which would silently split the transaction — is refused
outright as `TQL-YAML-1037`.

## Writing across databases: the projection pattern (slice 3)

`datasource:` is equally legal on the transactional recipes (`command-json`,
`webhook`, `queue-consume`, MCP tools): the *whole* command transaction — steps,
declarative validation, sequence-free plain SQL — runs on the named connector, with
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

Delivery semantics are Phase 27's, unchanged, because the *bus* never moves: the
event is written in the main command's transaction (a rolled-back command never
publishes), relayed onto the durable `tql_event` log on `main`, claimed with
`SKIP LOCKED` on `main`, deduplicated against `main`'s consumed-key records. Only
the consumer's **apply transaction** runs on `reporting`. The apply commit and the
consumed-mark are two transactions on two databases, so the honest contract is the
one Phase 27 already documents: **at-least-once delivery, effectively exactly-once
per idempotency key, and the projection SQL is an idempotent upsert** — a crash
between apply and acknowledge redelivers, the key check skips it, and the upsert
makes even the residual window harmless.

What a non-main transaction cannot carry is anything whose tables live on `main`:
`notify:`, `publish:`, `outbox:`, workflow transitions, `sequence:` allocation.
Declaring one is `TQL-YAML-1036` at build time — not a runtime surprise on a
connector that lacks `tql_outbox_event`. (A projection that must fan out further
does it on `main`: consume on `main`, publish again, project in a second consumer.)
Declarative `validate:` rules run on the route's connector by design — they are
checks against the state being written.

## Lint and error surface

| Code            | Severity | Meaning                                                                 |
| --------------- | -------- | ----------------------------------------------------------------------- |
| `TQL-YAML-1035` | error    | `datasource:` names a connector not declared under `tesseraql.datasources` |
| `TQL-YAML-1036` | error    | a non-main route declares a main-anchored feature (`notify:`, `publish:`, `outbox:`, workflow, `sequence:`) |
| `TQL-YAML-1037` | error    | a per-step `datasource:` inside a transactional pipeline (`steps:`)     |
| `TQL-SQL-2502`  | runtime  | the named datasource is not bound at execution time (existing)          |
| `TQL-APP-4201`  | runtime  | a `db/<name>/migration` tree names an undeclared connector (existing)   |

## Deliberately out of scope (documented, not implied)

- **JTA/XA and any two-connector transaction** — the stance above is the design.
- **Cross-connector joins or subqueries** — compose result sets, don't merge SQL.
- **Broker transports** (Kafka/JMS) — unchanged, still later opt-in leaf modules.
- **Studio's data browser and the docs portal's schema introspection** stay on
  `main` for now; extending them per-connector is a Studio-backlog item, not this
  phase.
- **Per-tenant named connectors** — tenancy remains a `main`-only concern.

**Milestone M18** — on a deployment with two real PostgreSQL databases: an app
declares a `reporting` connector and migrates it from `db/reporting/migration`; a
page renders one widget from `main` and one from `reporting`; creating an order
commits on `main` and a `queue-consume` projection upserts it into `reporting`;
a rolled-back command projects nothing; a forced redelivery never doubles a row —
all declared in YAML, and no XA anywhere in the stack.

Three slices: this design; **reads** (route-level + read-query `datasource:`,
per-connector dialect, tenancy precedence, `TQL-YAML-1035`/`1037`, schema +
reference regeneration); **projections** (transactional retarget, `TQL-YAML-1036`,
the two-database integration proof, and the messaging cookbook entry).
