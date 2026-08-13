# Jobs and scheduling

Routes answer requests; **jobs** do the work nobody is waiting on: nightly maintenance,
data exports, fetch-and-persist integrations. A job is a YAML document under the app's
`batch/` tree — conventionally `batch/**/params.yml` — with `kind: job`, and its steps are the
same plain 2-way SQL files the rest of the framework runs. Jobs fire on a schedule, on a
polled directory, or on demand through the operations API, and every run is recorded with
per-step status, timing, and row counts.

## One recipe

A job's work is its `pipeline:` — an ordered list of steps that run sequentially and pass
results forward. There is one spelling whatever the step count, so the smallest possible job
is a one-step pipeline:

```yaml
version: tesseraql/v1
id: user.purgeExpired
kind: job
recipe: batch-pipeline

trigger:
  schedule:
    cron: "0 0 3 * * ?"

pipeline:
  - id: purge
    sql:
      file: purge-expired.sql
      mode: update
```

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

A third trigger kind chains lightly: `after: <jobId>` fires the job when the named job's
execution **completes successfully** in the same app, carrying the parent's business date —
enough for "extract, then send". A trigger declares one kind; a chain must name a declared
job and must not loop (`TQL-BATCH-4209`). Anything wider than a chain — generational
reruns, cross-system dependencies, an operator console — belongs to the external scheduler
by design ([batch platform](batch-platform.md)).

## The business date

Business batch is date-driven: "run the 2026-07-31 close" and "rerun the 14th after
the fix" name a date the run is *for*, not the date it happens to run on. Every
execution carries one ([batch platform](batch-platform.md)):

- **`batch.businessDate`** is an ambient SQL bind in every step — seeded the way
  `audit.*` is seeded into commands, no `params:` wiring needed. `batch.executionId`
  rides along for correlation columns.
- It defaults to the firing's local date. A manual run overrides it with the reserved
  **`businessDate`** parameter (ISO `yyyy-MM-dd`) in the run request body; a malformed
  value refuses with `TQL-BATCH-4041` before anything executes.
- The date is **recorded** on the execution (`businessDate` in the operations API), so
  the audit trail distinguishes "ran on the 1st" from "ran the 31st's close on the
  1st".

```sql
insert into daily_totals (business_date, total)
select cast(/* batch.businessDate */ '2026-01-01' as date), sum(amount)
from   orders where order_date = /* batch.businessDate */ '2026-01-01'
```

## Business-day calendars

Cron cannot say "the last business day of the month" or "skip national holidays".
A business-day calendar can — declared once under `calendars/`, referenced from any
schedule ([batch platform](batch-platform.md) track B):

```yaml
# calendars/jp.yml
version: tesseraql/v1
calendars:
  jp-banking:
    weekend: [saturday, sunday]   # omit for the Saturday/Sunday default; [] means none
    holidays:
      source: { table: holidays, date: holiday_date, calendar: calendar_id }
      # or, for small fixed sets:  dates: [2026-01-01, 2026-01-02]
```

```yaml
trigger:
  schedule:
    cron: "0 0 2 * * ?"           # the cron says when to CONSIDER a firing
    calendar: jp-banking          # the calendar says whether it COUNTS
    runOn: last-business-day-of-month # business-day (default) | first-business-day-of-month
                                      # | last-business-day-of-month
```

This is the **daily-consider model**: "last business day of the month" is a daily cron
plus a filter, with no scheduler state. A filtered-out firing is skipped silently — it
is considered, claimed (so one node decides per firing), and **not a run**: no execution
is recorded. Holidays live in exactly one home per calendar: a fixed `dates:` list for
small closed sets, or a table-backed `source:` read on the job's datasource **at fire
time** — operations maintains next year's holidays as rows, no deploy. When the named
`calendar:` column is present, rows are filtered to the declaring calendar's name.

**The shifted nominal day** ("the 5th, or the next business day when it is a holiday")
is the same daily-consider model — the shift is a pure function of the calendar, so no
scheduler state exists anywhere:

```yaml
trigger:
  schedule:
    cron: "0 0 8 * * ?"
    calendar: jp-banking
    dayOfMonth: 5                 # the nominal day the run is FOR
    shift: next-business-day      # default; previous-business-day for pay-date-style rules
```

The firing counts only on the shifted target — and the run's **business date is the
nominal date**: the 5th's close, executed on the 7th, records the 5th
(`batch.businessDate`, the execution row, and the ops API all carry it). A shift can
cross a month boundary in either direction, and a `dayOfMonth` beyond the month's length
rounds down to its last day (the "31st" of April is the 30th). `dayOfMonth` and `runOn`
are mutually exclusive — one qualifier decides which firings count.

Two edges are deliberate:

- **Manual runs bypass the calendar.** An operator forcing a run through the ops API is
  saying "run it now"; the filter governs scheduled firings (and `tesseraql job run`,
  which is the scheduler surface — see below).
- **Resolution failures fail open, and say so.** If the calendar is not declared, or its
  holiday table cannot be read at fire time, the firing runs — silently skipping a
  close-job on a transient read error would be worse, and the job's own SQL reaches the
  same database next. But the skipped gate is recorded, not merely logged: the operations
  dashboard raises **`TQL-OPS-9009`** naming the job, the calendar, and the reason, for as
  long as the condition lasts (it clears itself once the calendar resolves again), and
  `tesseraql job run` prints the same on stderr. A job that should have been filtered out
  ran on a holiday — that is worth knowing before the business notices. The place typos
  get to be loud is still the build: lint checks every reference
  (`TQL-BATCH-4201`–`4203` below).

**Studio** has a job-policies form (`/_tesseraql/studio/ui/jobs`): a job's trigger —
schedule with its calendar qualifiers, or an `after:` chain picked from the declared
jobs — plus `overlap:` and `sla:` as structured fields, saved through the draft flow
with the linter's rules enforced before anything lands (`TQL-STUDIO-4239`).
Poll-triggered jobs keep the text editor: the poll block carries connector security the
form must not blur. And a calendars surface (`/_tesseraql/studio/ui/calendars`): every declared
calendar with a **month grid** that draws the daily-consider outcome — business days,
weekends, holidays, and, when previewing a `dayOfMonth:` rule, the nominal date and the
one day the firing actually counts. Weekend and fixed `dates:` edit as a form and land
as a draft (validated first, `TQL-STUDIO-4238`); table-backed holiday rows are data and
stay with the data browser. The preview reads table-backed holidays from the main
datasource.

A `file-import` job can instead declare a **`poll:` trigger** that watches a local, SFTP, or
FTPS directory and feeds each arriving file through the job's `import:` block, under a
deny-by-default host allow-list. Polling is part of the managed-connector surface — see
[managed connectors](connectors.md) for the full `poll:` reference.

**Manual runs** go through the operations API: `POST /_tesseraql/ops/batch/jobs/{jobId}/run`
(a **202** whose `Location` points at the execution detail — accepted, poll there)
(gated by the `ops.batch.run` policy) runs the job immediately and answers with the
execution id and final status. The JSON request body becomes the job's parameters. Declared
`input:` on the job document the expected names and types; each value is available to steps
as `params.<name>` — the same name a route's declared inputs bind under. Scheduled firings
run with no parameters, so a scheduled job's SQL must work when its `params.*` binds are
null.

## Driving jobs from an external scheduler

Enterprise batch estates already own a job-net scheduler; it drives work by executing a
command and branching on the exit code. That command is the CLI — in-process (manifest plus
datasource config, no server needed), with codes a scheduler can branch on:

```console
$ tesseraql job list --app .
$ tesseraql job run nightly.close --app . --business-date 2026-07-31 [--param k=v]…
$ tesseraql job rerun <executionId> --app . [--from-failed-step]
$ tesseraql job cancel <executionId> --app .   # the cooperative stop, see below
```

- **`job run`** waits, prints the execution id and per-step summary, and exits **0** on
  `COMPLETED`, **1** on `FAILED`, and **3** when the job's business-day calendar filtered
  the date out — distinct, so the scheduler can treat "holiday" as success-with-note
  (`--ignore-calendar` forces the run). A request that cannot run at all — an unknown job
  or execution id — exits 2. A completed run fires `after:` chains exactly as the serving
  runtime does; a failed chained job flips the exit code to 1 even though the parent's
  success stands, because a scheduler must hear about a broken link.
- **`job rerun`** starts a new execution with the source run's **recorded parameters and
  business date** (parameters are recorded on every execution for exactly this), so it
  re-runs the same fact — and a [chunk step](#the-chunk-step) resumes from its checkpoint.
  `--from-failed-step` additionally records the steps the source execution completed as
  `SKIPPED`, starting at its first failure.
- `notify:` steps enqueue on the durable outbox — the serving runtime delivers them when
  it next runs. A `perTenant` job runs untenanted under the CLI; drive per-tenant runs
  through the operations API.

## Pipeline steps and the step context

A step **is a binding with an `id`**, plus the output blocks that act on what it produced.
Its keys fall on three axes, and a step declares at least one:

| Axis | Keys | How many |
| --- | --- | --- |
| Acquisition or statement | `sql:` (a 2-way SQL file), `http:` (one synchronous outbound REST request) | at most one |
| Output | `export:` (write a formatted file, [below](#the-export-step)), `push:` (deliver a produced file, [below](#the-push-step)), `notify:` (enqueue a notification on the transactional outbox) | any, beside the arm |
| Processing | `chunk:` (restartable per-row processing, committed in slices, [below](#the-chunk-step)) | at most one |

So a step that extracts rows and writes them to a file is one step with two keys — the arm
reads, the output block writes — and a step that reports what it wrote adds `notify:` beside
them.

Steps run in order, and each step publishes its result into a shared context that later
steps bind from:

| Context path               | Value                                                              |
| -------------------------- | ------------------------------------------------------------------ |
| `params.<name>`            | a job parameter                                                    |
| `steps.<id>.affectedRows`  | rows affected by an earlier `mode: update` step                    |
| `steps.<id>.rows` / `.rowCount` / `.first` | an earlier read step's result — a `sql:` query or an `http:` call, the same envelope either way |
| `steps.<id>.spool` / `.rowCount` | an earlier `mode: query-spool` step's spool reference        |
| `steps.<id>.status` / `steps.<id>.body` / `steps.<id>.headers` | an earlier `http:` step's response, beside its rows |
| `steps.<id>.eventId`       | the outbox event id an earlier `notify:` step enqueued             |
| `steps.<id>.transferId` / `steps.<id>.filename` / `steps.<id>.rows` | an earlier `export:` step's produced transfer |
| `steps.<id>.target` / `steps.<id>.filename` | an earlier `push:` step's delivery |
| `tenant.id`                | the current tenant, on a [per-tenant job](#per-tenant-jobs)        |

A SQL step names its file (relative to the job's directory) and an execution mode:
`update` for statements that modify rows, `query` to read them, or `query-spool` to stream the
result set to a temporary JSONL spool. The file stays runnable in a plain SQL tool; binds are
marked with `/* name */ dummy` comments:

```yaml
pipeline:
  - id: deactivate
    sql:
      file: deactivate-pending.sql
      mode: update
      params:
        cutoff: params.businessDate      # bound from the step context
  - id: report
    notify:
      channel: audit-webhook
      payload:
        deactivated: steps.deactivate.affectedRows
```

The `notify:` step is the job-side twin of a command's `notify:` block — same channels, same
outbox delivery, same per-user opt-out and declarative test cases; see
[notifications](notifications.md). The `http:` step interleaves an allow-listed
outbound REST call with SQL steps; see [managed connectors](connectors.md).

An `http:` step is an acquisition like any other, so it publishes what any read publishes:
`select:` names the part of the response that becomes rows, and the step's `rows` /
`rowCount` / `first` sit beside the call's `status` / `body` / `headers`. Its `mode:` is the
one a call has — `query` (default, the rows are held) or `query-spool` (they are streamed to a
spool, [below](#loading-what-another-step-read)); `update` on a call is refused at build
time, because a call reads.

```yaml
pipeline:
  - id: headcount
    http:
      url: https://api.directory.example/v1/headcount
      select: units
      credential: directory
  - id: record
    sql:
      file: record-headcount.sql
      mode: update
      params:
        total: steps.headcount.first.total    # a response row, bound like a query's
```

## The chunk step

A per-row rewrite too large for one transaction — revalue a million orders, anonymize
inactive accounts — needs what a single SQL step cannot give: intermediate commits, a
restart that does not start over, and a policy for the one bad row
([batch platform](batch-platform.md) track C):

```yaml
pipeline:
  - id: revalue
    chunk:
      reader: { sql: { file: unprocessed-orders.sql } }  # SELECT, keyset-ordered
      writer: { sql: { file: revalue-order.sql } }       # runs once per row
      key: id                                    # the reader column checkpoints track
      commitEvery: 1000                          # default 500
      onError: skip                              # default: fail
      skipLimit: 100                             # default 100 when skipping
```

**Two connections.** The reader streams its SELECT on one connection (a fetch-sized
cursor); the writer runs once per row on a second connection that commits every
`commitEvery` handled rows. The writer's binds are the reader's row (**`row.<column>`**)
plus the ambient `batch.*` context and the job's `params.*`.

**Checkpoint restart.** After each committed chunk, the last handled `key` value lands in
the managed `tql_job_checkpoint` table (one row per job, step, and business date). A rerun
for the **same business date** finds it and binds it as **`chunk.after`**; a completed step
clears it. The reader's contract is keyset pagination, guarded so a fresh run reads from
the top — and since checkpoint values bind as strings, a numeric key casts its bind:

```sql
select id, amount
from   orders
/*%if chunk.after != null */
where  id > cast(/* chunk.after */ '0' as bigint)
/*%end*/
order by id
```

**The skip policy.** With `onError: skip`, a writer failure on one row rolls back to a
per-row savepoint (the failed statement cannot poison the chunk's transaction), is recorded
in the managed `tql_job_skips` table with the row key and message, and processing continues
— until `skipLimit` (default 100) is exceeded, which discards the uncommitted chunk and
fails the step. `onError: fail` (the default) fails the step on the first writer error. Skipped
rows advance the checkpoint like processed ones: they were handled and recorded, not lost.
Processed and skipped counts land on the step execution (`affectedRows` / `skippedRows`),
the operations API (`skips` on the execution detail), and the console's steps table.

Two lints guard the restart contract, because it lives in the reader's SQL where only the
build can see it: a reader without `order by` is an error (`TQL-BATCH-4207` — the resume
point would be undefined), and a reader that never binds `chunk.after` is a warning
(`TQL-BATCH-4208` — a restart reprocesses from the top, which only an idempotent writer
survives). The `key` column's values must be unique and ascending under the reader's
`order by`; the gallery's `user.anonymizeInactive` job is the runnable reference.

### Loading what another step read

A reader declares **`spool:`** instead of `sql:` to load an earlier step's spool. That is what
makes a copy between two databases expressible: the extract runs on one connector, the load on
the job's, and neither side holds the result.

```yaml
kind: job
datasource: erp_b                                  # the load side
pipeline:
  - id: extract
    sql:
      file: extract-orders.sql
      mode: query-spool
      datasource: erp_a                            # the extract side
  - id: load
    chunk:
      reader: { spool: steps.extract.spool }
      writer: { sql: { file: upsert-order.sql } }
      key: id
```

A batch step may name its own `datasource:` **only for a read**: each batch step owns its
transaction, so an extract elsewhere splits nothing, while a write on another connector would be
a second transaction the job does not own (`TQL-YAML-1037`). There is still no distributed
transaction — the copy is eventual, explicit and restartable, which is
[the stance](multi-datasource.md) the whole surface takes.

Two consequences worth knowing before you rely on it:

- **The spool is the snapshot.** A rerun re-reads exactly the rows the extract saw, which a
  SQL-reading chunk cannot offer — its source table has moved on. Spool retention has to cover
  the rerun window.
- **Values round-trip through JSON.** A writer binding a date or a decimal casts in SQL, the
  same rule `chunk.after` carries. It matters more here, because a JSON number landing in a
  numeric column is the common case rather than the exception.

Anything that fills a spool can feed a chunk, not only SQL. Spooling is not a SQL feature — it
is what a large result does on its way to a consumer that reads it once — so `mode:
query-spool` means the same thing on an `http:` acquisition, and the same reader loads it:

```yaml
pipeline:
  - id: fetch
    http:
      url: https://directory.example/companies
      select: companies
      mode: query-spool                  # the rows land in the spool, not in memory
  - id: load
    chunk:
      reader: { spool: steps.fetch.spool }
      writer: { sql: { file: upsert-company.sql } }
      key: code
```

That closes a gap the surface would otherwise leave: fetching a large result from an API and
writing it into the database had no expressible shape — a single statement bound to a call's
response holds every row, and the only alternative was a file round trip through `push:` and a
poll trigger. Routing it through the spool rather than teaching the chunk reader an `http:` arm
keeps paging and retries on the acquisition side, and leaves the reader with one thing to
understand: a spool is a spool, whoever filled it.

One honest limit: the gateway buffers the response body, so the spool bounds what the *rest of
the job* holds, not the call itself. A result too large for one response wants the API's own
paging, one call per page.

## The export step

The scheduled "close the day, write the report" move: an `export:` step is the
[route recipes' export vocabulary](file-transfers.md) — `format`, `filename`,
`columns`, `locale`/`timezone`, workbook and PDF `template:` options — on a pipeline
step, run on the job's datasource:

```yaml
pipeline:
  - id: refresh
    sql: { file: load-summary.sql, mode: update }
  - id: report
    export:
      format: excel
      filename: price-summary-{batch.businessDate}.xlsx
      sql: { file: report.sql, mode: query }
      columns:
        - { name: category, label: Category }
        - { name: total, label: Total, type: number, format: "#,##0" }
  - id: announce
    notify:
      channel: reports
      payload:
        rows: steps.report.rows
        transferId: steps.report.transferId
```

- **Same machinery, synchronous shape.** The extraction streams through the format codec
  into the managed spool and records the same execution and transfer rows an HTTP
  `file-export` records — the step just runs it inline, on the job's thread, and fails
  the step on error instead of leaving a status to poll. The transfer's route id is
  `<jobId>#<stepId>`, so the console tells job-produced files from route-produced ones.
- **The extraction SQL renders like any step's**: the dialect variant beside the
  file, ambient `batch.*` binds, file placeholders against the job's datasource. On a
  `duckdb` datasource this is the analytics report in one step — `report.sql` reads
  Parquet, lake tables, or an attach, and the codec writes CSV, Excel, or PDF.
- **`filename:` interpolates `{dotted.path}` context values** — `{batch.businessDate}`
  being the one that matters. `template:` resolves beside the job file; `locale:` and
  `timezone:` are literals (a job has no request to resolve them from).
- **`after:` runs in the extraction transaction.** `timing: download` stays route
  vocabulary — a job-produced file's download is an ops action, not a business signal
  (`TQL-YAML-1041` at build time).
- **Retrieval is the [operations console](ops-console.md)**: the transfers page links every completed
  export, and machine callers fetch
  `GET /_tesseraql/ops/batch/transfers/{transferId}/file` under `ops.batch.view`. The
  step publishes `transferId`, `rows`, and `filename` into the step context, so a
  follow-up `notify:` carries the pointer — or, on a mail channel, the file itself:
  `attach: steps.report.transferId` sends the produced file as a mail attachment
  ([notifications](notifications.md#the-notify-step-on-a-job)) — and an `http:`
  can tell a partner system the drop is ready.

## The push step

The delivery leg the export step stops short of: a `push:` step sends a produced
transfer to a partner drop — the outbound mirror of the [`poll:`
trigger](connectors.md#the-poll-trigger-for-file-import), under the mirrored policy block:

```yaml
pipeline:
  - id: report
    export:
      format: csv
      filename: price-summary-{batch.businessDate}.csv
      sql: { file: report.sql, mode: query }
  - id: deliver
    push:
      transport: sftp                  # local | sftp | ftps
      host: partner.example.com
      path: /drop/incoming
      credential: partner-sftp         # tesseraql.connectors.push.credentials
      file: steps.report.transferId
      as: prices-{batch.businessDate}.csv   # optional; default: the transfer's filename
```

- **Deny by default, mirrored from poll.** A remote target's host must be in
  `tesseraql.connectors.push.allowedHosts`; its `credential:` names an entry under
  `tesseraql.connectors.push.credentials` (username plus exactly one of `password:` or
  `privateKeyFile:`), so a job never carries a credential. The push block is separate
  from the poll block on purpose — whom an app accepts files from and whom it delivers
  to are different trust decisions. SFTP host keys pin against the push block's
  `knownHostsFile`; FTPS requires the push block's `trustStore` and sends
  `PBSZ 0`/`PROT P`, exactly the [poll-side guarantees](connectors.md) — the endpoint
  mechanics are one shared implementation, so the two directions cannot drift apart.
- **A `local` target writes under `tesseraql.connectors.push.allowedPaths`** — the
  deny-by-default root rule every other filesystem surface follows.
- **Delivery is atomic for the partner's poller**: the file is staged (`.part`
  locally, a dot-name remotely) and renamed into place, so a partner never reads a
  partial file.
- **`file:` names the transfer** — typically the export step's
  `steps.<id>.transferId`, but any transfer id the context can supply. Reading it
  counts as the transfer's first download. `as:` renames the delivery
  (`{dotted.path}` placeholders resolve against the job context; a bare filename
  only — separators are refused at build time, `TQL-YAML-1042`).
- **A failed delivery fails the job** — connect, authenticate, or write errors are
  `TQL-BATCH-5315` on the step, so the rerun story and `sla:` alerting apply
  unchanged; a re-run re-delivers under the same name, which the rename semantics
  make an overwrite, not a duplicate.

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

## Overlap, and the SLA that pages someone

By default a firing that finds the previous execution still `RUNNING` is recorded
`SKIPPED`, naming the running execution — auditable, not a run (`overlap: skip`).
A job whose runs are safe to stack — an idempotent poll, say — declares the
alternative explicitly:

```yaml
overlap: concurrent    # run anyway, even while the previous execution is RUNNING
sla:
  completeBy: "06:00"          # a day's run must have COMPLETED by this wall-clock time
  runningLongerThan: 2h        # a running execution beyond this raises the alert
```

A skipped firing is an execution row with status `SKIPPED` and no steps — visible in the
console and the API like any run, and `tesseraql job run` exits **3** for it (did not run
by policy, the calendar-filtered answer). The check is a cheap read against the execution
table; scheduled firings are already serialized by the cluster claim. A `queue` policy is
deliberately deferred.

`sla:` is checked by a periodic managed sweep (every `tesseraql.batch.slaSweepInterval`,
default `60s`) that raises **`ops.jobSla`** through the configured alerts channel —
`completeBy` when the day's business date has no `COMPLETED` execution past the deadline
(once per date), `runningLongerThan` when an execution exceeds the duration (once per
execution). **Alert-only by design**: killing an in-flight JDBC statement safely is its own
project, and a false sense of "timeout means stopped" is worse than an honest page.
Malformed declarations refuse at build time (`TQL-BATCH-4210`).

## Stopping a run

What an operator needs is not a kill switch but a stop button that tells the truth. A
running execution can be asked to stop — **cooperatively**:

```console
$ tesseraql job cancel <executionId> --app .          # or:
$ curl -X POST …/_tesseraql/ops/batch/executions/{id}/cancel   # policy ops.batch.run
```

The request sets a flag in the execution row (so it reaches a run on any node, or another
terminal's in-process run), and the executor polls it at two boundaries:

- **between pipeline steps** — remaining steps never start; the execution ends `STOPPED`
  with the message saying so;
- **at every chunk commit** — the stop lands exactly on a committed checkpoint, the step
  ends `STOPPED` with its real processed/skipped counts, and a
  [rerun](#driving-jobs-from-an-external-scheduler) for the same business date resumes
  precisely there. Nothing is lost and nothing is reprocessed.

The semantics are stated, not implied: the stop takes effect **at the next boundary**, and
an individual statement is bounded by its SQL timeout, not preempted. Cancelling an
execution that is not running answers `409` with `TQL-BATCH-4042` — a finished run has
nothing left to stop.

## Observing runs

Every run is persisted as an execution with its steps, visible three ways:

- the **operations console** (`/_tesseraql/ops/console`) lists recent batch executions —
  job, app, status, trigger, duration — and each links to a per-step detail screen. Its
  jobs page tells the whole trigger story (calendar qualifiers included), shows the
  `overlap:`/`sla:` policies as badges, and — because a calendar-filtered firing leaves
  no execution row by design — a **Calendar next** column: the next date the calendar
  lets a firing count, shifted nominal dates included (`2026-08-03 (for 2026-07-31)`);
- the **operations API**: `GET /_tesseraql/ops/batch/jobs` (declared jobs as
  `{id, app, trigger, overlap, sla}` objects — the same trigger story the CLI prints),
  `GET /_tesseraql/ops/batch/executions` and `.../executions/{id}` (runs and step detail),
  all bearer-authenticated and gated by the `ops.batch.view` policy. `ops.app.<name>`
  grants scope which apps' jobs and executions a caller sees;
- **logs and traces**: each run logs its completion or failure, every job and step is a
  span in the trace tree (with the owning app and affected rows), and slow step SQL shows
  up in the slow-SQL view like any other statement;
- **metrics**: every finished run counts on the Prometheus exposition
  ([observability](deployment.md)) — `tesseraql_job_runs_total` labelled
  `job`/`app`/`status` (COMPLETED, FAILED, STOPPED, SKIPPED each count under their
  own status, so "did tonight's close run" is one query) and a
  `tesseraql_job_duration_seconds` histogram per job.

## Error codes

| Code | Meaning |
| --- | --- |
| `TQL-BATCH-4040` | the operations API was asked about a job or execution that does not exist — or that the caller's app scope does not include |
| `TQL-BATCH-4041` | the reserved `businessDate` run parameter is not an ISO date (`yyyy-MM-dd`) |
| `TQL-BATCH-4042` | the cancel target is not a RUNNING execution — nothing left to stop (HTTP 409) |
| `TQL-BATCH-4201` | a schedule names a calendar that no `calendars/*.yml` declares (lint) |
| `TQL-BATCH-4202` | `runOn:` without a `calendar:`, or an unknown `runOn:` value (lint) |
| `TQL-BATCH-4203` | a calendar declares both `dates:` and `source:` — holiday rows have exactly one home (lint) |
| `TQL-BATCH-4204` | the same calendar name is declared in two `calendars/*.yml` documents |
| `TQL-BATCH-4205` | a calendar that cannot be evaluated: an unknown weekend day name, a non-ISO holiday date, or a `source:` whose table/columns are not plain identifiers |
| `TQL-BATCH-4206` | a malformed `chunk:` — missing `reader:`/`writer:` files, `commitEvery` below 1, or a negative `skipLimit` (lint) |
| `TQL-BATCH-4207` | a chunk reader without `order by` — no deterministic resume point (lint) |
| `TQL-BATCH-4208` | a chunk reader that never binds `chunk.after` — restarts reprocess from the top (lint warning) |
| `TQL-BATCH-4209` | an `after:` chain naming an unknown job, or a chain that loops (lint) |
| `TQL-BATCH-4210` | an unknown `overlap:` policy, or an `sla:` that is empty or does not parse (lint) |
| `TQL-BATCH-5001` | the execution store could not record a run |
| `TQL-BATCH-5002` | a step failed (its SQL raised an error), a chunk step exceeded its `skipLimit`, or a step is misdeclared |
| `TQL-BATCH-5315` | a `push:` delivery failed — connect, authenticate, write, or rename |
| `TQL-SEC-4141` | a `push:` target host outside `tesseraql.connectors.push.allowedHosts` (deny by default) |

The `notify:` and `http:` step families report their own codes in the same domain
(channels `TQL-BATCH-5301`…, outbound HTTP `TQL-BATCH-5305`…); see
[notifications](notifications.md), [managed connectors](connectors.md), and the
[error-code reference](reference-error-codes.md).

Lint checks jobs statically:

| Finding | Code |
| --- | --- |
| A step declaring no work at all, two bindings, or a `chunk:` beside a binding | `TQL-FIELD-2004` |
| A malformed `push:` step: no transfer reference, an unknown transport, a remote target without host or credential, or a non-bare delivered name | `TQL-YAML-1042` |
| A malformed `export:` step: no extraction query, no format, or a `download`-timed follow-up. The pdf template checks and the datasource refusal are shared with routes | `TQL-YAML-1041` |
| A job with both a schedule and a poll trigger, or a malformed poll source | `TQL-YAML-1005` |
| A poll job without its `import:` block | `TQL-YAML-1006` |
| Non-allow-listed poll or HTTP egress | `TQL-SEC-4070`, `TQL-SEC-4080` |
| Calendar qualifiers that would fail open at fire time | `TQL-BATCH-4201`–`4203` |
| A chunk step whose restart contract is broken or unstated | `TQL-BATCH-4206`–`4208` |

## Related pages

- [guide-integration.md](guide-integration.md) — the reading order for an integration or batch application.

- [Managed connectors](connectors.md) — `http:` steps, the `poll:` trigger, egress policy
- [Notifications](notifications.md) — the `notify:` step, channels, alerts, notify test cases
- [Transactional writes](transactional-writes.md) — the command-side transaction model jobs deliberately differ from
- [Deployment](deployment.md) — multi-node semantics and operations permissions
- [Batch platform](batch-platform.md) — the campaign map: business dates, calendars, chunked
  restart, the external-scheduler CLI contract
