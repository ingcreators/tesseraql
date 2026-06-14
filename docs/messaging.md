# Messaging and events

TesseraQL apps emit and consume domain events through **messaging channels** (roadmap Phase 27):
governed recipes for publish/subscribe between commands and other systems. A command publishes an
event on the same transactional outbox it uses for notifications, and a `queue-consume` route runs a
SQL pipeline for every message it receives, with at-least-once delivery and idempotency-key
deduplication.

Two built-in, broker-free transports share the same durable table, so a broker-free app gets a real
event bus from the database it already runs — no Kafka, no JMS:

- **`pg-notify`** — PostgreSQL `LISTEN`/`NOTIFY` for low-latency wake-ups (PostgreSQL only).
- **`db-poll`** — the same table, polled on the backstop interval (**every dialect**: MySQL, SQL
  Server, Oracle, and PostgreSQL behind a transaction-pooling proxy that breaks `LISTEN`).

Both give identical at-least-once, idempotent delivery; they differ only in latency. Broker
transports (Kafka, JMS) arrive later as opt-in leaf modules. The `publish:`/`consume:` YAML is
identical across all transports, so moving between them changes only configuration.

## The signal-and-log model

`LISTEN`/`NOTIFY` alone is **at-most-once**: a notification is lost if no consumer is connected, and
its payload is capped at 8 KB. So TesseraQL never trusts it with a message:

> **`NOTIFY` is the low-latency *signal*; the durable `tql_event` table is the *log*.**

A published event is a row in `tql_event`. A consumer claims it with `FOR UPDATE SKIP LOCKED` (the
same "PostgreSQL as a queue" pattern the outbox dispatcher uses), runs its pipeline, and marks it
consumed. `NOTIFY` only wakes the consumer the instant a row appears; a polling **backstop** sweeps
on an interval so a missed signal never strands a message. Durability lives in the table — the wake
signal only decides *when* to drain, never *whether* a message survives.

## Publishing an event

A command route adds a `publish:` block. Like `notify:`, the event is written **in the command's
transaction** as a transactional outbox event, so a rolled-back command never publishes and a
committed one publishes at-least-once after the commit.

```yaml
version: tesseraql/v1
id: orders.create
kind: route
recipe: command-json
input:
  orderId: { type: string, required: true }
  total:   { type: number }
sql:
  file: insert-order.sql
  mode: update
  params:
    orderId: body.orderId
    total: body.total
publish:
  channel: events                 # a channel under tesseraql.messaging.channels
  topic: orders.created           # the logical event name a consumer subscribes to
  key: body.orderId               # optional ordering/idempotency key (a source expression)
  payload:                        # resolved against the command context, like notify:
    orderId: body.orderId
    total: body.total
```

`publish:` rides a transactional command (`command-json`, `webhook`, or `queue-consume`), never a
read route — a synchronous outbound call would belong in an [`http-call`](connectors.md) step
instead. The published event id is available to later context as `publish.eventId`.

## Consuming events

A **`queue-consume`** route lives under `consume/` (not `web/` — it is not an HTTP endpoint) and runs
its SQL pipeline once per message:

```yaml
# consume/orders/project.yml
version: tesseraql/v1
id: orders.project
kind: route
recipe: queue-consume
consume:
  channel: events
  topic: orders.created
  idempotencyKey: body.orderId    # a redelivery of this key is a no-op (effectively exactly-once)
input:                            # declare the message shape — the mass-assignment guard applies
  orderId: { type: string, required: true }
  total:   { type: number }
sql:                              # or steps: — runs in one transaction, like a command
  file: project-order.sql
  mode: update
  params:
    orderId: body.orderId
    total: body.total
```

The message body is the published payload, bound into the context exactly like a request body — so
`body.orderId` and the `input:` constraints work the same way. **A consumer must declare `input:`
for the fields it reads**: the deny-by-default mass-assignment guard rejects an undeclared field,
just as it does for an HTTP route.

`idempotencyKey` resolves a business key from the message; when omitted, the publisher's `key` is
used. The first delivery of a key runs the pipeline and records the key; a redelivery is recognised
and skipped (acknowledged without writing a row). Combined with at-least-once delivery, this makes
processing **effectively exactly-once per business key** — so a consumer's SQL should be an
idempotent upsert.

## Channel configuration

Channels are configured centrally, so a route names a channel but carries no transport detail:

```yaml
tesseraql:
  messaging:
    channels:
      events:
        transport: pg-notify      # pg-notify (default) | db-poll
    backstop: 10s                 # poll interval: the db-poll cadence, and the pg-notify safety net
```

Both transports use the app's main datasource and the same `tql_event` log; `pg-notify` additionally
holds a dedicated `LISTEN` connection. Choose by dialect and latency:

| Transport   | Dialects                                   | Latency                 | When                                                |
| ----------- | ------------------------------------------ | ----------------------- | --------------------------------------------------- |
| `pg-notify` | PostgreSQL only                            | near-instant (NOTIFY)   | the default on PostgreSQL                            |
| `db-poll`   | PostgreSQL, MySQL, SQL Server, Oracle      | up to the poll interval | MySQL/SQL Server/Oracle, or PostgreSQL behind PgBouncer transaction pooling |

`db-poll` is the portable floor: it claims off `tql_event` with the dialect's `SKIP LOCKED`
equivalent (PostgreSQL/MySQL `LIMIT … FOR UPDATE SKIP LOCKED`, Oracle `ROWNUM … FOR UPDATE SKIP
LOCKED`, SQL Server `TOP … WITH (UPDLOCK, READPAST)`), exactly as the outbox dispatcher does. There
is no in-database push to wait on, so latency is the `backstop` interval — lower it for tighter
delivery, at the cost of more idle polling.

Relaying published events onto channels requires the outbox dispatcher, so enable it as for
notifications:

```yaml
tesseraql:
  outbox:
    dispatch:
      fixedDelay: 1s              # the dispatcher relays publish: events onto their channels
```

## At-least-once, end to end

```
command commit
  → outbox EVENT (written in the command transaction)
  → outbox dispatcher → channel-publish relay → tql_event row + NOTIFY
  → pg-notify consumer claims (SKIP LOCKED), woken by NOTIFY or the backstop poll
  → queue-consume SQL pipeline
  → idempotency-key dedup → mark consumed
```

Every hop is durable: the outbox guarantees the event survives the commit, and `tql_event`
guarantees it survives until a consumer acknowledges it. A failed consume is retried until a
dead-letter ceiling (`tesseraql.outbox.maxAttempts`), visible to operators like any outbox event.

## Operational notes

- **Connection pooling** (pg-notify): `LISTEN`/`NOTIFY` needs a session-pinned connection. It does
  **not** work through PgBouncer in transaction or statement pooling mode — use session pooling, a
  direct connection for the app's main datasource, or switch the channel to `db-poll`.
- **Payload size** (pg-notify): a `NOTIFY` carries only a wake signal, never the payload, so the
  8 KB `NOTIFY` limit never applies to message size; the payload lives in `tql_event`.
- **Throughput** (pg-notify): `NOTIFY` serialises at commit time, which is ample for
  line-of-business volumes but not a high-throughput firehose. For that, a broker transport (a later
  slice) is the fit.
- **Latency vs. polling** (db-poll): with no in-database push, a consumer sees a message at most one
  `backstop` interval after it is published. A shorter interval tightens delivery but polls an idle
  channel more often; the durable table makes either choice correct, only faster or slower.
- **Dialect**: the durable `tql_event` queue is portable (`SKIP LOCKED` exists on PostgreSQL, MySQL
  8.0+, Oracle, and SQL Server via `READPAST`), so `db-poll` runs everywhere. `LISTEN`/`NOTIFY` is
  PostgreSQL-only, so `pg-notify` runs only on a PostgreSQL main datasource — consistent with the
  roadmap's "PostgreSQL first" capability matrix. A `pg-notify` channel on a non-PostgreSQL
  datasource is logged and left idle (switch it to `db-poll`).

## Governance and testing

Lint catches a misconfigured channel before it ships:

| Code            | Severity | Meaning                                                                      |
| --------------- | -------- | ---------------------------------------------------------------------------- |
| `TQL-SEC-4090`  | error    | a `queue-consume` route names a channel not in `tesseraql.messaging.channels` |
| `TQL-SEC-4091`  | error    | a `publish:` block names a channel not in `tesseraql.messaging.channels`     |
| `TQL-YAML-1009` | error    | a `queue-consume` route has no `consume.channel`/`consume.topic` or no pipeline |
| `TQL-YAML-1010` | error    | `publish:`/`consume:` declared on a recipe that does not support it           |
| `TQL-YAML-1106` | error    | a malformed or unknown-transport `tesseraql.messaging.channels` entry         |

A `queue-consume` route is covered by the `queue-consume` coverage kind when a declarative suite
exercises its SQL (the same SQL-file basis as route coverage); gate it with
`coverage.thresholds.queue-consume`.
