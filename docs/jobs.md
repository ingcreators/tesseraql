# Jobs and scheduling

Routes answer requests; **jobs** do the work nobody is waiting on: nightly maintenance,
data exports, fetch-and-persist integrations. A job is a YAML document under the app's
`batch/` tree — conventionally `batch/**/job.yml` — with `kind: job`, and its steps are the
same plain 2-way SQL files the rest of the framework runs. Jobs fire on a schedule, on a
polled directory, or on demand through the operations API, and every run is recorded with
per-step status, timing, and row counts.

## Two recipes

- **`batch-tasklet`** — the job is a single SQL statement, declared in a top-level `sql:`
  block. Use it when there is exactly one thing to do.
- **`batch-pipeline`** — the job is an ordered `pipeline:` of steps that run sequentially
  and pass results forward. Use it when steps feed each other, or when the job mixes SQL
  with a notification or an outbound HTTP call.

A tasklet is the smallest possible job:

```yaml
version: tesseraql/v1
id: user.purgeExpired
kind: job
recipe: batch-tasklet

trigger:
  schedule:
    cron: "0 0 3 * * ?"

sql:
  file: purge-expired.sql
  mode: update
```

Internally a tasklet runs as a one-step pipeline, so everything below about steps,
executions, and failure behavior applies to both recipes.

## Scheduling

A job declares at most one trigger; a job without one only runs on demand.

```yaml
trigger:
  schedule:
    cron: "0 0 2 * * ?"     # Quartz cron, seconds first
```

or

```yaml
trigger:
  schedule:
    fixedDelay: 15m         # short duration string: ms / s / m / h / d
```

`cron` takes a Quartz cron expression (seconds-first). `fixedDelay` re-fires at
a fixed period. Declare one or the other, not both.

A `file-import` job can instead declare a **`poll:` trigger** that watches a local, SFTP, or
FTPS directory and feeds each arriving file through the job's `import:` block, under a
deny-by-default host allow-list. Polling is part of the managed-connector surface — see
[managed connectors](connectors.md) for the full `poll:` reference.

**Manual runs** go through the operations API: `POST /_tesseraql/ops/batch/jobs/{jobId}/run`
(gated by the `ops.batch.run` policy) runs the job immediately and answers with the
execution id and final status. The JSON request body becomes the job's parameters. Declared
`params:` on the job document the expected names and types; each value is available to steps
as `job.<name>`. Scheduled firings run with no parameters, so a scheduled job's SQL must
work when its `job.*` binds are null.

## Pipeline steps and the step context

Each pipeline step has an `id` and declares **exactly one** of:

- `sql:` — render and execute a 2-way SQL file
- `notify:` — enqueue a notification on the transactional outbox
- `http-call:` — issue one synchronous outbound REST request

Steps run in order, and each step publishes its result into a shared context that later
steps bind from:

| Context path               | Value                                                              |
| -------------------------- | ------------------------------------------------------------------ |
| `job.<name>`               | a job parameter                                                    |
| `step.<id>.affectedRows`   | rows affected (update) or returned (query) by an earlier step      |
| `step.<id>.status` / `step.<id>.body` / `step.<id>.headers` | an earlier `http-call` step's response |
| `step.<id>.eventId`        | the outbox event id an earlier `notify:` step enqueued             |
| `tenant.id`                | the current tenant, on a [per-tenant job](#per-tenant-jobs)        |

A SQL step names its file (relative to the job's directory) and an execution mode:
`update` for statements that modify rows, `query` to execute and count, or `query-spool` to
stream the result set to a temporary JSONL spool — the spool reference and row count are
published to later steps, which keeps arbitrarily large extracts out of memory. The file
stays runnable in a plain SQL tool; binds are marked with `/* name */ dummy` comments:

```yaml
pipeline:
  - id: deactivate
    sql:
      file: deactivate-pending.sql
      mode: update
      params:
        cutoff: job.businessDate      # bound from the step context
  - id: report
    notify:
      channel: audit-webhook
      payload:
        deactivated: step.deactivate.affectedRows
```

The `notify:` step is the job-side twin of a command's `notify:` block — same channels, same
outbox delivery, same per-user opt-out and declarative test cases; see
[notifications](notifications.md). The `http-call:` step interleaves an allow-listed
outbound REST call with SQL steps; see [managed connectors](connectors.md).

## Transactions

Each SQL step runs on its own connection and commits independently. A job is **not** one
transaction: when step three fails, steps one and two stay committed and are not rolled
back. Write job SQL so a rerun after a partial failure is safe — idempotent updates, or
statements guarded by the state they change. This is the deliberate opposite of a
`command-json` route, whose steps share a single all-or-nothing transaction — see
[transactional writes](transactional-writes.md). Work that must be atomic with a business
write belongs in a command; a job is for work that can be resumed.

## Per-tenant jobs

On a multi-tenant app, `perTenant: true` makes each firing run the job once per configured
tenant, each run on that tenant's own datasource with `tenant.id` available as a SQL bind.
Every tenant run is a separate execution record.

## Cluster safety and failure behavior

On a multi-node deployment every node hosts every scheduled job, but **exactly one node runs
each firing**: before running, a node claims the firing by inserting a claim row keyed on
the job and its fire time into a shared database table — the first insert wins, every other
node skips. Cron firings share the cron's computed fire time; fixed-delay firings are
aligned to their period window so independently started nodes still agree on the key. The
claim key includes the owning app, so two apps sharing a database never contend for each
other's jobs. Old claims are pruned automatically. No broker or leader election is involved
— any shared database is enough; see [deployment](deployment.md) for the multi-node notes.

When a step fails, the step and the execution are marked `FAILED` with the error message,
and the remaining steps do not run. There is **no automatic retry**: the next scheduled
firing is the next attempt, or an operator reruns the job manually. (Notifications a job has
already enqueued are the exception — the outbox dispatcher retries those independently.)
With `tesseraql.notifications.alerts.channel` configured, every failed execution raises an
`ops.jobFailure` alert through that channel — see the operations-alerts section of
[notifications](notifications.md).

## Observing runs

Every run is persisted as an execution with its steps, visible three ways:

- the **operations console** (`/_tesseraql/ops/console`) lists recent batch executions —
  job, app, status, trigger, duration — and each links to a per-step detail screen;
- the **operations API**: `GET /_tesseraql/ops/batch/jobs` (declared jobs),
  `GET /_tesseraql/ops/batch/executions` and `.../executions/{id}` (runs and step detail),
  all bearer-authenticated and gated by the `ops.batch.view` policy. `ops.app.<name>`
  grants scope which apps' jobs and executions a caller sees;
- **logs and traces**: each run logs its completion or failure, every job and step is a
  span in the trace tree (with the owning app and affected rows), and slow step SQL shows
  up in the slow-SQL view like any other statement.

## Error codes

| Code | Meaning |
| --- | --- |
| `TQL-BATCH-4040` | the operations API was asked about a job or execution that does not exist — or that the caller's app scope does not include |
| `TQL-BATCH-5001` | the execution store could not record a run |
| `TQL-BATCH-5002` | a step failed (its SQL raised an error), or a step is misdeclared |

The `notify:` and `http-call:` step families report their own codes in the same domain
(channels `TQL-BATCH-5301`…, outbound HTTP `TQL-BATCH-5305`…); see
[notifications](notifications.md), [managed connectors](connectors.md), and the
[error-code reference](reference-error-codes.md).

Lint checks jobs statically: a step declaring both or neither of `sql:`/`notify:`/
`http-call:` (`TQL-FIELD-2004`), a job with both a schedule and a poll trigger or a
malformed poll source (`TQL-YAML-1005`), a poll job without its `import:` block
(`TQL-YAML-1006`), and non-allow-listed poll or HTTP egress (`TQL-SEC-4070`,
`TQL-SEC-4080`).

## Related pages

- [Managed connectors](connectors.md) — `http-call` steps, the `poll:` trigger, egress policy
- [Notifications](notifications.md) — the `notify:` step, channels, alerts, notify test cases
- [Transactional writes](transactional-writes.md) — the command-side transaction model jobs deliberately differ from
- [Deployment](deployment.md) — multi-node semantics and operations permissions
