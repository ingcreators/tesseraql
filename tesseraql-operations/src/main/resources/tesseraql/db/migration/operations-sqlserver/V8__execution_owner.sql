-- A batch run has an owner and a pulse (docs/audit-hardening.md Decision 6), SQL Server variant.

if col_length('tql_job_execution', 'owner_node') is null
alter table tql_job_execution add owner_node varchar(200);

if col_length('tql_job_execution', 'heartbeat_at') is null
alter table tql_job_execution add heartbeat_at datetime2;
