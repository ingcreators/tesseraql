# SQL execution shapes

Implementation design for the follow-on to [contract SQL
execution](contract-sql-execution.md): the last hand-rolled readers adopt the statement
primitive, and the primitive gains the two execution shapes it does not have — **JDBC batch
execution** for high-volume writers, and **stored calls** with declared OUT parameters.

Written 2026-08-23, after #1006/#1007 resolved that campaign's recorded residual. At that
point every executor of rendered 2-way SQL meets JDBC through `io.tesseraql.core.sql
.SqlStatement` except three recorded readers, and two execution patterns are unrepresentable
on any declared surface. This design closes the first list and opens the second, measured
against main at #1007.

## What remains, measured

`SqlExecutorLedgerTest` names three main-source files that still run *declared* SQL through
their own `prepareStatement` (the rest of the ledger is fixed-SQL stores and dev tooling,
out of scope by the parent design):

| Executor | Runs | What its read does that the primitive's forms did not |
| --- | --- | --- |
| `batch.SqlStepRunner` | job `sql:` step | materializes under a cap with **raw values** (a later step binds them; an ISO string is not a `timestamp`), or spools via `SpooledRows.drain` |
| `batch.ChunkStepRunner` | job `chunk:` step | streams the reader at a computed fetch size; the writer **prepares once per rendered-SQL variant and executes per row** behind savepoints |
| `enrich.KeyedReference` | enrich lookups | materializes under a cap with **raw values** and the raw-plus-lowercase-alias label double entry (the one undeclared label policy left) |

The reasons they stayed recorded no longer hold for the *reads*: #1006 shipped the
caller-owned `SpannedReader`, and #1007 shipped `fetchSize` (the forward-only streaming
prepare). What genuinely has no primitive shape yet:

- **A reusable writer.** The chunk writer prepares once per rendered-SQL variant and
  executes ten thousand times. Every primitive form prepares and closes per call — correct
  for a route, real per-row cost for a chunk.
- **JDBC batching.** The framework's own fixed-SQL stores already use
  `addBatch`/`executeBatch` in four places (outbox claim, event-channel claim, org closure,
  TOTP scratch codes); no *declared* statement can.
- **Stored calls.** No `prepareCall` exists anywhere in main sources. A PostgreSQL function
  is reachable today as `mode: query` over `select f(…)`, and the write path's
  `execute()`/`getUpdateCount()` tolerates a native `CALL` that answers no count — but OUT
  parameters, the reason stored procedures are called rather than selected, are
  unrepresentable.

Two measured facts steer the span policy below:

- The chunk step opens **one** `tesseraql.sql.execute` span for its whole run
  (`mode: chunk`), covering the reader and every writer row — the same per-phase policy the
  file-transfer service keeps by design (parent doc, slice 7). Per-row spans on a
  100k-row chunk would flood the trace ring and poison the ops rates.
- The job `sql:` step's span carries a `stepId` attribute the primitive's spans cannot
  express, because the primitive has no seam for caller-declared span attributes.

## Structural decision 1: the remaining readers are `SpannedReader`s, and values stay typed

`SqlStepRunner`'s materializing read and `KeyedReference`'s reference read convert through
the reader seam with their existing loops **as the reader** — not through `cappedRows`.
`cappedRows` shapes values through `ResultRows.value` (temporals become ISO strings), which
is right for a response body and wrong here: a job step's rows are bound into later
statements and a reference's columns are composed into rows a writer binds, so a
`java.sql.Timestamp` must stay one. The caps, the overflow refusals and their messages stay
the caller's, inside the reader. The spool read (`mode: query-spool`) and the chunk reader
run inside the reader seam the way #1007's export does: the drain happens while the
primitive holds the statement open.

## Structural decision 2: the primitive takes caller-declared span attributes

`SqlStatement` gains an `attribute(key, value)` wither: every span the executor opens
carries the accumulated attributes. `SqlStepRunner` declares `stepId` once
(`statements.attribute("stepId", step.id())`) and its update form stops needing a hand
span. This replaces the job step's per-site span with the primitive's; two recorded deltas
ride it: the span's `mode` says what JDBC did (`query`, for a spool step that used to stamp
`query-spool` — the slow-SQL log keeps recording `query-spool`), and the span records the
classified `SqlStatementException` where it used to record the wrapping TQL-BATCH-5002 (the
step span above still records the wrapper).

## Structural decision 3: a per-phase caller hands the primitive no tracer, and says so

The chunk step (and the file-transfer service, when it adopts) spans per **phase**, not per
statement — the declared exception the parent design's slice 7 already made. The rule made
explicit: such a caller builds its `SqlStatement` **without** `tracer(...)`, keeps its phase
span, and writes a comment at the construction site naming this policy. The primitive's
span machinery is a no-op without a tracer, so nothing double-spans and nothing is
silently suppressed at a distance. Bounds, classification and the leak-safe prepare still
apply — the phase span is a telemetry choice, not an executor exemption.

## Structural decision 4: KeyedReference's labels become normalized — the last label policy lands

Structural decision 7 of the parent design declared two label policies and converted every
executor but one. `KeyedReference`'s raw-label-plus-lowercase-alias double entry becomes
**normalized** (`ResultRows.label`), values untouched. For the lowercase binds and match
columns enrichments use in practice, normalized and the lowercase alias agree everywhere,
Oracle included. **The recorded behavioral edge**: a reference SQL whose alias is *quoted
mixed case* today publishes both `"displayName"` and `displayname`; after this change only
`displayName` — a template reading the lowercase shadow of a quoted alias reads the alias
itself instead. Enrichment statements also gain spans (`surface=enrich`, already in the
vocabulary) and the reference read's sqlId is the reference's source path.

## Structural decision 5: one batch handle, order-preserving, flushing on variant change

The primitive gains a caller-driven handle for the prepare-once/execute-many shape:

```java
try (SqlStatement.Rows writer = statements.rows(connection, sqlId)) {
    writer.execute(bound);      // one row now, via the cached prepared statement
    writer.add(bound);          // or: queue for the next executeBatch
    int affected = writer.flush();
}
```

- **One prepared statement per rendered-SQL text**, cached inside the handle — the chunk
  writer's cache, moved rather than reinvented. The declared bound applies at prepare;
  every failure leaves classified.
- **`add` preserves order.** 2-way SQL renders per row, so consecutive rows can render
  different statements (`/*%if*/` on a row's values). Queuing them into per-variant batches
  and executing at the end would reorder writes against the same table — an insert and its
  update swapping places. The handle instead **flushes the pending batch whenever the
  incoming row's SQL differs from the batch's**, so execution order is row order, and the
  batch win is real exactly when rows share a shape (the common case).
- **`flush` is one executed statement and opens one span** (`mode: batch`, `batchSize` and
  the summed `affectedRows` as attributes — under a per-phase caller's no-tracer policy,
  none). `close()` with rows still pending **refuses**: a handle must never drop queued
  writes on the floor (the silent-tolerance rule).
- A `BatchUpdateException` is classified like any `SQLException`. Which row failed is
  driver-defined — the JDBC spec lets a driver stop at the first failure or continue — so
  the handle reports the executed counts it got and does not invent a row.

## Structural decision 6: chunk batching is opt-in, and it trades row attribution for throughput

`chunk:` gains `batch: true`. The writer then queues each row (`add`) and flushes at the
`commitEvery` boundary, before the commit that carries the checkpoint — one round trip per
chunk instead of one per row. The declared trade:

- **`batch: true` requires the default `onError: fail`** — declaring it with
  `onError: skip` is a build-time refusal. Skip semantics are per-row by definition
  (a savepoint around each row, the row's key in `tql_job_skips`), and a batch cannot
  attribute a member failure to a row on every driver. An operator who wants skips keeps
  the per-row writer; an operator who wants throughput accepts that a failure fails the
  chunk (which then rolls back to the last checkpoint and reruns from there — the
  restartability contract is unchanged).
- The failure message names the chunk's key **range**, not one row.

The default path also converts: per-row writes go through the handle's `execute`, keeping
the statement cache and the savepoint-per-row skip machinery in the chunk writer, where the
skip accounting lives. With that, `ChunkStepRunner` leaves the ledger.

## Structural decision 7: a stored call is a 2-way statement whose OUT parameters are declared binds

A command step gains `mode: call`. The statement is ordinary 2-way SQL — the driver's call
escape or the dialect's native syntax — and **an OUT parameter is a bind site in the
reserved `out.` namespace**:

```sql
{call reprice_order(/* orderId */'o-1', /* out.newTotal */null)}
```

```yaml
steps:
  reprice:
    file: reprice-order.sql
    mode: call
    params: {orderId: path.id}
    out: {newTotal: numeric}
```

Rendering already does the hard part: bind sites become `?` placeholders in order, and each
`BoundParameter` carries its expression (`out.newTotal`), so the executor knows which
positions to `registerOutParameter` instead of `setObject` — no new parser node, no
positional bookkeeping in YAML. The primitive gains the one form that needs
`prepareCall`:

```java
Map<String, Object> outs = statements.call(connection, sqlId, bound, outTypes);
```

- `out:` maps each name to a declared JDBC type from a small keyword set (`varchar`,
  `numeric`, `integer`, `bigint`, `boolean`, `date`, `timestamp`, `double`); an unknown
  keyword is a build-time refusal.
- **All-or-nothing, both ways**: a rendered `out.*` expression with no declaration, or a
  declaration no bind site renders, refuses naming the mismatch — the SCIM contract-set
  rule applied to one statement.
- OUT values read back by position, published as `steps.<name>.out.<name>`, values through
  `ResultRows.value` (they land in the response/context, not in another bind).
- `expect:`/`keys:` are refused on a call step (they count affected rows, which a call does
  not answer); `when:` and the ambient binds work unchanged. The `out` bind namespace is
  reserved on call steps the way `audit` is reserved everywhere.
- The span is the primitive's, `mode=call`; failures classify like every other statement.

**Deliberate v1 boundaries, recorded**: OUT only — an INOUT (a bind that both sets and
registers) is representable in this scheme and deferred until asked for; the function
return-value form `{? = call f(…)}` is out — its leading placeholder is not a bind site,
and a function's return is already reachable as `mode: query` over `select f(…)`; job
`sql:` steps do not take `mode: call` yet — after slice 1 that is one switch arm plus the
same `out:` declaration on the job model, deferred until a job needs it. PostgreSQL's
JDBC driver routes the call escape through its `escapeSyntaxCallMode` connection property
(default treats `{call f(…)}` as a function invocation); the bundled integration test uses
a function with OUT parameters — the shape that works on the default — and a deployment
calling true procedures sets the property on its datasource, which is the driver's
documented contract, not ours to paper over.

## Slices

Four, in dependency order, each its own PR from fresh main; every adoption shrinks
`SqlExecutorLedgerTest`.

1. **The job `sql:` step is a caller** — `SqlStatement.attribute(key, value)`;
   `SqlStepRunner` adopts (update via the write form, materialize and spool via
   `SpannedReader`, raw values kept); the runner's per-site span goes. Ledger −1.
2. **The enrichment reference is a caller** — `KeyedReference.executeReference` through the
   reader seam; labels normalized (structural decision 4, the behavioral change recorded in
   the CHANGELOG); `surface=enrich` spans. Ledger −1.
3. **The reusable writer and JDBC batching** — `SqlStatement.Rows` (structural decision 5);
   the chunk reader streams through the reader seam under the no-tracer phase policy, the
   writer's cache and savepoints move onto the handle, `chunk.batch: true` with the
   `onError: skip` refusal. Ledger −1: the ledger's declared-SQL residue is zero.
4. **Stored calls** — the `call` form on the primitive, `mode: call` + `out:` on command
   steps, the build-time refusals, and a PostgreSQL integration test calling a function
   with OUT parameters end-to-end through a command route.

## Guards and tests

- **Per-slice revert-the-fix**: slice 1 and 2 convert under their existing suites
  (`BatchJobIntegrationTest`, the enrichment ITs) — behavior-preserving by suite, plus new
  unit coverage for `attribute()` and the label change.
- **Slice 3**: a unit test drives `Rows` through variant interleaving (A,B,A) and asserts
  flush-on-change and order; the refusal on close-with-pending; a chunk IT runs
  `batch: true` end-to-end and asserts the `onError: skip` refusal at build.
- **Slice 4**: unit tests for out-registration by expression namespace and the
  all-or-nothing refusals; the PostgreSQL IT proves a call with OUT parameters through the
  whole command pipeline.
- `SqlExecutorLedgerTest` enforces each departure; `GeneratedReferenceTest` and the YAML
  surface guards regenerate on the model additions.

## Deliberately not in this design

- **Batching for the file-transfer import path.** The same handle fits; it adopts as its
  own change when named, with its per-phase spans kept.
- **`mode: call` on job steps and routes.** Recorded above with the direction; a route-level
  call has no obvious customer (a route reads or commands).
- **Result-set-returning calls** (ref cursors). A dialect minefield with no current ask;
  `mode: query` over a function covers the portable case.
- **Driver-level batch tuning** (`rewriteBatchedInserts` and friends). Datasource
  configuration, documented where datasources are.

## Open questions

1. **Does `Rows.flush()` return the summed count or the per-statement counts?** — *settled*:
   the sum, matching `affectedRows` everywhere else; a caller needing per-row counts is
   using the wrong shape (that is what `execute` is for).
2. **Should `batch: true` change `commitEvery`'s default?** — *settled*: no; the batch is
   the transaction's rows, one flush per commit, and 500 stays a sane batch size.
3. **INOUT parameters** — deferred until a deployment names one; the bind-site scheme
   extends to them (a non-null rendered value plus registration) without new syntax.
