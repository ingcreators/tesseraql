-- Who triggered a job execution (docs/ops-console-actions.md): null for scheduled and
-- system-initiated runs, the principal's login id for manual runs. SQL Server variant,
-- guarded the same way as V1's object_id checks.

if col_length('tql_job_execution', 'triggered_by') is null
alter table tql_job_execution add triggered_by varchar(256);
