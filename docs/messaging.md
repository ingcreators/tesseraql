# Messaging and events

TesseraQL apps emit and consume domain events through **messaging channels** (roadmap Phase 27):
governed recipes for publish/subscribe between commands and other systems. A command publishes an
event on the same transactional outbox it uses for notifications, and a `queue-consume` route runs a
SQL pipeline for every message it receives, with at-least-once delivery and idempotency-key
deduplication.

The built-in transport is **`pg-notify`**: a durable PostgreSQL table plus `LISTEN`/`NOTIFY`, so a
broker-free app gets a real event bus from the database it already runs — no Kafka, no JMS. Broker
transports arrive later as opt-in leaf modules; the `publish:`/`consume:` YAML is identical across
transports, so moving between them changes only configuration.

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
        transport: pg-notify      # the built-in transport; the default if omitted
    backstop: 10s                 # consumer poll backstop (default 10s)
```

The `pg-notify` transport uses the app's main datasource: the `tql_event` log and a dedicated
`LISTEN` connection. Relaying published events onto channels requires the outbox dispatcher, so
enable it as for notifications:

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

## Operational notes (pg-notify)

- **Connection pooling**: `LISTEN`/`NOTIFY` needs a session-pinned connection. It does **not** work
  through PgBouncer in transaction or statement pooling mode — use session pooling or a direct
  connection for the app's main datasource.
- **Payload size**: a `NOTIFY` carries only a wake signal, never the payload, so the 8 KB `NOTIFY`
  limit never applies to message size; the payload lives in `tql_event`.
- **Throughput**: `NOTIFY` serialises at commit time, which is ample for line-of-business volumes
  but not a high-throughput firehose. For that, a broker transport (a later slice) is the fit.
- **Dialect**: the `SKIP LOCKED` table queue is portable, but `LISTEN`/`NOTIFY` is PostgreSQL-only,
  so the `pg-notify` transport runs only on a PostgreSQL main datasource — consistent with the
  roadmap's "PostgreSQL first" capability matrix. A queue-consume route declared against another
  dialect is logged and left idle until a broker transport is configured.

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
