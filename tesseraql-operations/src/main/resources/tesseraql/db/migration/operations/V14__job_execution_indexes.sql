-- Execution sweep indexes (docs/jobs.md, "Who owns a run"): tql_job_execution carried nothing but
-- its primary key while gaining a row for every run, every file transfer and every inline export
-- step. Both recurring sweeps read it on a timer, on every node, so both were table scans that
-- grew with history.
--
-- (job_id, status) serves the per-job reads that already existed — the reaper's reapAbandoned, the
-- overlap: skip check and the SLA sweep's findRunning, one per declared job per firing.
-- (app_name, status) serves the transfer sweep, which is app-scoped because a transfer started by
-- a route has no job id to be swept under.
--
-- Both lead with an equality column other than status, so neither can be chosen for a read whose
-- status is COMPLETED — the bulk of the table — where a range scan would be worse than the scan it
-- replaced. Idempotency via the bootstrap's tolerated duplicate-index errors, as V11 does.

create index idx_tql_job_execution_job on tql_job_execution (job_id, status);
create index idx_tql_job_execution_app on tql_job_execution (app_name, status);
