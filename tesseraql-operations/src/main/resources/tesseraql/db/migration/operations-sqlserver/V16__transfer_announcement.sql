-- What a transfer's follow-up announces when it commits (docs/list-export.md), SQL Server
-- variant: guarded so the script stays re-runnable, as V13's and V15's columns are.

if col_length('tql_file_transfer', 'emit_json') is null
alter table tql_file_transfer add emit_json varchar(max);
if col_length('tql_file_transfer', 'invalidates_json') is null
alter table tql_file_transfer add invalidates_json varchar(max);
if col_length('tql_file_transfer', 'emit_tenant_id') is null
alter table tql_file_transfer add emit_tenant_id varchar(64);
