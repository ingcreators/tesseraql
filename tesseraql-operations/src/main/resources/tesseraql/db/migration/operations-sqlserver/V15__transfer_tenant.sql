-- The tenant a transfer was resolved for (docs/multi-tenancy.md), SQL Server variant: guarded
-- so the script stays re-runnable, as V13's column is.

if col_length('tql_file_transfer', 'tenant_id') is null
alter table tql_file_transfer add tenant_id varchar(64);
