-- The cooperative stop (docs/batch-platform.md, a lifted deferral): an operator's cancel
-- request, polled by the running executor at step and chunk-commit boundaries. A flag
-- rather than a status flip, so the runner's own completion writes never race it.

if col_length('tql_job_execution', 'cancel_requested') is null
alter table tql_job_execution add cancel_requested datetime2;
