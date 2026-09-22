-- Who started a transfer (docs/job-inbox.md decision 1), SQL Server variant: guarded so the
-- script stays re-runnable, as V15's and V16's columns and the task-queue indexes are.

if col_length('tql_file_transfer', 'subject') is null
alter table tql_file_transfer add subject varchar(256);
if not exists (select 1 from sys.indexes where name = 'idx_tql_file_transfer_owner') create index idx_tql_file_transfer_owner on tql_file_transfer (app_name, subject, created_at);
