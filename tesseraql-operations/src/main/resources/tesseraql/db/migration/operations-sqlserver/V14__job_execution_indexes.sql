-- Execution sweep indexes (docs/jobs.md, "Who owns a run"), SQL Server variant: guarded so the
-- script stays re-runnable, as V11 does. Each guard is one line, because the bootstrap splits the
-- script on every semicolon.

if not exists (select 1 from sys.indexes where name = 'idx_tql_job_execution_job') create index idx_tql_job_execution_job on tql_job_execution (job_id, status);
if not exists (select 1 from sys.indexes where name = 'idx_tql_job_execution_app') create index idx_tql_job_execution_app on tql_job_execution (app_name, status);
