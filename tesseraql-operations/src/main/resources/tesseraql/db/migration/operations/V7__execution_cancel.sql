-- The cooperative stop (docs/batch-platform.md, a lifted deferral): an operator's cancel
-- request, polled by the running executor at step and chunk-commit boundaries. A flag
-- rather than a status flip, so the runner's own completion writes never race it.

alter table tql_job_execution add column cancel_requested timestamp;
