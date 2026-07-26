-- Who triggered a job execution (docs/ops-console-actions.md): null for scheduled and
-- system-initiated runs, the principal's login id for manual runs. The plain ADD COLUMN
-- stays idempotent through the bootstrap's tolerated duplicate-column errors.

alter table tql_job_execution add column triggered_by varchar(256);
