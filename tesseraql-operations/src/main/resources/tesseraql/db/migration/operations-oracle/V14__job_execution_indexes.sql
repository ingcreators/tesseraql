-- Execution sweep indexes (docs/jobs.md, "Who owns a run"), Oracle variant: same statements as the
-- common script; the bootstrap tolerates ORA-00955 on the re-run.

create index idx_tql_job_execution_job on tql_job_execution (job_id, status);
create index idx_tql_job_execution_app on tql_job_execution (app_name, status);
