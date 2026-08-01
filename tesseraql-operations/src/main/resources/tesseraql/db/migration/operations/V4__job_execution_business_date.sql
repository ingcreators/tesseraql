-- The business date a run was FOR (docs/batch-platform.md track A): "ran on the 1st"
-- and "ran the 31st's close on the 1st" are different facts, and the difference is
-- audit-grade. Null on rows recorded before the column existed.
alter table tql_job_execution add column business_date date;
