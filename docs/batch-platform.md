# Batch platform — the executor enterprise schedulers drive

> **Status: designed, not built.** The batch follow-up to the web-runtime maturation:
> what `docs/jobs.md` ships today is a sound skeleton (two recipes, Quartz cron /
> fixed delay, poll triggers, per-tenant fan-out, claim-row cluster safety, execution
> history, failure alerts, `query-spool`), and what business-application batch still
> needs is exactly five things: a business date, a business-day calendar, restartable
> chunked processing, an external-scheduler execution contract, and overlap/SLA
> hardening.

## The stance: be the best executor, not another scheduler

Enterprise batch estates already own a job-net scheduler (JP1, Control-M, Airflow,
plain cron). TesseraQL does not compete with the DAG, the generational reruns, or the
operator console those provide. It competes on the **execution unit**: a declarative,
SQL-first job that knows its business date, respects the business-day calendar,
commits in restartable chunks, reports typed outcomes, and can be driven equally by
its own cron trigger or by an external scheduler calling one CLI verb with meaningful
exit codes. Full job-net orchestration is an explicit **non-goal**; the light
`after:` chaining in track D is the ceiling.

## Track A — the business date: `batch.businessDate`

Business batch is date-driven — "run the 2026-07-31 close", "rerun the 14th after
the fix" — and today a scheduled firing runs with **no parameters at all** (jobs.md
documents that job SQL "must work when its `job.*` binds are null"; the concept is
absent, not optional).

- A new ambient bind namespace for job SQL: **`batch.businessDate`** (a SQL `date`)
  and `batch.executionId`, seeded into every execution's step context the way
  `audit.*` is seeded into commands.
- Defaults: the firing's local date (the runtime zone). A manual run — ops API or
  CLI — overrides it with the reserved parameter `businessDate` (ISO date). A rerun
  reuses the recorded date, not today's.
- **Recorded**: `tql_job_execution` gains `business_date`; the ops console and API
  show which date a run was *for* — the difference between "ran on the 1st" and "ran
  the 31st's close on the 1st" is audit-grade.
- Suites bind it like any params namespace (`params: { batch: { businessDate: … } }`).

## Track B — business-day calendars: `calendars/`

Cron cannot say "the last business day of the month" or "skip national holidays".
The shared-definition precedent (domains/, rules/, decisions/ — and decision tables'
table-backed `source:`) extends naturally:

```yaml
# calendars/jp.yml
version: tesseraql/v1
calendars:
  jp-banking:
    weekend: [saturday, sunday]
    holidays:
      source: { table: holidays, date: holiday_date, calendar: calendar_id }
      # or, for small fixed sets:  dates: [2026-01-01, 2026-01-02, …]
```

A schedule gains qualifiers:

```yaml
trigger:
  schedule:
    cron: "0 0 2 * * ?"          # fires daily — the cron says when to CONSIDER
    calendar: jp-banking          # the calendar says whether it COUNTS
    runOn: lastBusinessDayOfMonth # businessDay | firstBusinessDayOfMonth | lastBusinessDayOfMonth
```

- **The daily-consider model**: the cron fires, the calendar filters. "Last business
  day of month" is a daily cron plus a filter — no deferred one-shot firings, no
  scheduler state. A filtered-out firing is skipped silently (it is not a run).
  Holiday-shift semantics (`5日, 休日なら翌営業日`) are deliberately **deferred**:
  they require remembering a missed nominal date across firings, and v1 refuses to
  half-build that; the workaround (daily cron + a guard on the business date in SQL)
  is documented.
- Table-backed holidays are read at fire time on the job's datasource — operations
  maintains next year's holidays as rows, no deploy (the tolerances-table story).
- Lints: a schedule naming an unknown calendar, `runOn:` without `calendar:`, a
  calendar declaring both `dates:` and `source:` (one home for its rows).
- The editor/symbols catch-up (calendars array, `calendar:` completion) follows the
  established #478/#519/#543 pattern, in its own slice when the surface settles.

## Track C — the chunk step: restartable, skip-aware, committed in slices

The missing Spring-Batch muscle. A pipeline step gains a fourth body:

```yaml
pipeline:
  - id: revalue
    chunk:
      reader: { file: unprocessed-orders.sql }   # SELECT, keyset-ordered
      writer: { file: revalue-order.sql }        # runs once per row
      key: id                                    # the reader column checkpoints track
      commitEvery: 1000
      onError: { skipLimit: 100 }
```

- **Two connections**: the reader streams its SELECT (fetch-sized cursor, its own
  connection); the writer runs per row on a second connection that commits every
  `commitEvery` rows. The writer's binds are the reader's row (`row.*`) plus the
  ambient `batch.*`/`job.*` context.
- **Checkpoint restart**: after each committed chunk, the last row's `key` value is
  written to a managed `tql_job_checkpoint` row (job, step, business date). A rerun
  for the same business date finds the checkpoint and seeds it as the
  **`chunk.after`** bind — the reader's contract is
  `where <key> > /* chunk.after */ … order by <key>` (fresh runs bind null). A step
  that completes clears its checkpoint.
- **Skip policy**: a writer failure on one row is recorded in the managed
  `tql_job_skips` table (execution, step, row key, message) and processing continues
  — until `skipLimit` is exceeded, which fails the step. `skipLimit: 0` (default)
  keeps today's fail-fast. Processed/skipped counts land on the step execution and
  the ops console.
- **Lints**: a chunk reader without `order by` is an error (no deterministic
  restart); a reader that never binds `chunk.after` is a warning (it will reprocess
  from the top on restart — legal for idempotent writers, worth saying out loud).
- Non-goals: parallel partitions (a later track if demand appears), reader
  re-execution per page (the streaming cursor is simpler and equally restartable).

## Track D — the external-scheduler contract: CLI verbs and exit codes

External schedulers drive batch by executing a command and branching on its exit
code. Today the only manual trigger is an ops HTTP endpoint.

- **`tesseraql job list --app .`** — declared jobs, their triggers, calendars.
- **`tesseraql job run <jobId> --app . [--param k=v]… [--business-date DATE]`** —
  runs the job in-process (manifest + datasource config, no server needed — the
  `tesseraql test` precedent), waits, prints the execution id and per-step summary,
  and exits **0** on `COMPLETED`, **1** on `FAILED`, **3** when the calendar filtered
  the run out (distinct, so a scheduler can treat "holiday" as success-with-note).
- **`tesseraql job rerun <executionId> --app .`** — a new execution with the source
  run's recorded parameters and business date; chunk checkpoints make it resume
  where the failure stopped. `--from-failed-step` additionally skips the pipeline
  steps the source execution completed (recorded as `SKIPPED`), starting at its
  first failure.
- **Light chaining**: `trigger: { after: <jobId> }` fires a job when the named job's
  execution completes successfully in the same app — enough for "extract, then
  send"; anything wider belongs to the external scheduler by design.

## Track E — overlap, and the SLA that pages someone

- **Overlap policy**: today a firing runs even while the previous execution is still
  `RUNNING`. `overlap: concurrent` (the current behavior) stays the default;
  `overlap: skip` records a `SKIPPED` execution naming the running one — auditable,
  alertable, and cheap to check against the execution table. A `queue` policy is
  deferred.
- **SLA alerts** (the workflow deadline-sweeper precedent): a job may declare
  `sla: { completeBy: "06:00" }` and/or `sla: { runningLongerThan: 2h }`; a periodic
  managed check raises `ops.jobSla` through the configured alerts channel when a
  day's expected run has not completed by the deadline, or a run has been going too
  long. **Alert-only in v1**: killing an in-flight JDBC statement safely is its own
  project, and a false sense of "timeout means stopped" is worse than an honest
  page.

## Out of scope (named, so they stay decisions)

- **Job-net/DAG orchestration** — external schedulers own it; `after:` is the ceiling.
- **Holiday-shift firing** (`nextBusinessDay` deferral) — needs missed-date memory;
  documented workaround until a real design.
- **Fixed-length/Shift_JIS file formats, count trailers** — demand-driven follow-up
  on the poll/import and spool surfaces.
- **Hard kill on timeout, `queue` overlap, parallel chunk partitions** — deferred.

## Slices

1. **Business date** (track A): `batch.*` ambient binds, `business_date` on the
   execution record + ops surfaces, the reserved `businessDate` parameter, docs.
2. **Calendars** (track B): `calendars/` shared definition, schedule qualifiers +
   fire-time filter, lints; gallery gains a calendar-gated job.
3. **Chunk step** (track C): reader/writer/checkpoint/skips, managed tables, lints,
   ops-console counts; gallery conversion.
4. **CLI contract** (track D): `job list/run/rerun`, exit codes, `--from-failed-step`,
   `after:` chaining.
5. **Overlap + SLA** (track E): `overlap: skip`, `sla:` alerts through the existing
   alerts channel.

Each slice re-proves the gallery and updates `docs/jobs.md` in place — this document
is the campaign map, jobs.md stays the user guide.
