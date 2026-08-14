# Migrating the lake to DuckDB 1.5

Status: **investigated 2026-08-14, not started.** `duckdb_jdbc` stays pinned at 1.3.1.0 and
Dependabot is told to leave it alone; this page is why, and what the work is.

DuckDB 1.5 changes DuckLake in two ways that the framework's lake story is built on top of.
Neither is a defect — both are upstream doing something reasonable — and neither is visible
from a version number. Everything below was measured, not read.

## What changes

### Writes are inlined until they are flushed

A write of up to ten rows (`ducklake_default_data_inlining_row_limit`, new in 1.5, default 10)
is kept in the metadata catalog rather than written as a data file. The rows are queryable
through the lake immediately and **absent from the `data:` path**, so anything reading that
path directly — `read_parquet`, an external tool, the retention job — does not see them.
`ducklake_flush_inlined_data('lake')` materializes them.

| | 1.3.1 | 1.5.5 |
| --- | --- | --- |
| Parquet files after a two-row insert | 1 | 0 |
| After `ducklake_flush_inlined_data` | (no such function) | 1 |

**The decision, taken 2026-08-14: the app flushes, explicitly.** The framework does not turn
inlining off on the operator's behalf (`DATA_INLINING_ROW_LIMIT 0` on the `ATTACH` would), and
does not flush on the app's behalf. A write is the app's, and so is the moment it becomes a
file. The cost of that choice is that forgetting the flush is silent: the rows are readable
through the lake and missing from the data path, with nothing reported.

### Reclaiming files needs a rewrite, not just an expiry

Retention was two verbs: expire the snapshots, delete the files nothing references. On 1.5 that
reclaims nothing, and the reason is not a bug. A file holding a deleted row also holds the rows
that were **not** deleted, so it stays live. Something has to write the survivors somewhere else
before the old file becomes garbage.

Measured, three appends then deleting two of three rows:

| After | Parquet files |
| --- | --- |
| the appends | 3 |
| the delete | 5 (a delete writes deletion files) |
| `expire_snapshots` + `cleanup_old_files` | 5 |
| `merge_adjacent_files` as well | 5 |
| **`rewrite_data_files`** as well | **1** |

Rows readable throughout: unchanged. So retention becomes three verbs in this order —
`ducklake_rewrite_data_files`, `ducklake_expire_snapshots`, `ducklake_cleanup_old_files` —
and `ducklake_delete_orphaned_files` remains a separate concern (files the catalog never knew
about, such as the debris of an interrupted write).

## What is already fixed

One thing this investigation found is not about DuckDB at all, and shipped separately: a
`mode: update` step used `executeUpdate`, which JDBC specifies to refuse for a statement that
answers with rows. Any maintenance call is that shape. It now runs the statement with
`execute` and reports `getUpdateCount`, which is -1 when the driver had a result set instead of
a count.

## What is not understood

With the two changes above applied — the gallery app flushing after its append, its retention
job rewriting before expiring — `pricing.pruneNow` still fails on 1.5:

```
Not implemented Error: Scanning a DuckLake table after the transaction has ended
```

It fails only through the batch job. The same statement, on the same app state, on the
runtime's own pooled datasource, succeeds. Ten shapes were tried and none reproduced it:

| Tried | Reproduced? |
| --- | --- |
| `executeUpdate` / `execute` / draining the result | no |
| autocommit / an explicit transaction | no |
| the runtime's fence (`SET GLOBAL lock_configuration=true`) | no |
| the catalog on PostgreSQL, as the app declares it | no |
| `setQueryTimeout`, as the step applies it | no |
| a connection that attached before the writes (a pooled one) | no |
| flushing on a different connection from the write | no |
| `PreparedStatement` rather than `Statement` | no |
| the runtime's own pooled connection, real app state | no |
| flush and rewrite in one file, one statement batch | **still fails** |

The stack shows the refusal inside `duckdb_jdbc_execute` — the execution itself, not a later
read. What is known about the trigger: it needs the job's own `retire` step to have deleted
rows first. When a probe deleted them before the job ran, the job's rewrite succeeded.

**Where to start:** run a job containing only the rewrite step against a state where rows were
deleted by a different job, and compare with one where the same job deletes and rewrites. That
isolates whether the step boundary or the deletion carries the state that DuckLake objects to.
The ten rows above do not need retrying.

## The shape of the work

1. The inlining contract: flush in the job that writes, documented in `duckdb.md`.
2. Retention as three verbs, in the gallery app and in `duckdb.md`, with the reason the rewrite
   cannot be skipped.
3. Whatever the unexplained failure turns out to be.
4. `InventoryLakeIntegrationTest` and `RemoteLakeIntegrationTest`, which encode the old
   behaviour: M24 asserts that the parquet count strictly drops after pruning, which stays a
   fair assertion once the rewrite is in the job.
5. Unpin `org.duckdb:duckdb_jdbc` in `.github/dependabot.yml`.
