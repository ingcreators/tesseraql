-- The parameters a run was started with (docs/batch-platform.md track D), recorded so
-- `tesseraql job rerun` re-runs the same fact: the source run's parameters and business
-- date, not whatever today would have defaulted. Null on rows recorded before the column.

alter table tql_job_execution add params_json varchar(max);
