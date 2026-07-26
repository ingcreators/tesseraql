-- Who triggered a job execution (docs/ops-console-actions.md): null for scheduled and
-- system-initiated runs, the principal's login id for manual runs. Idempotent through
-- the bootstrap's tolerated ORA-01430 (column already exists).

alter table tql_job_execution add triggered_by varchar2(256);
