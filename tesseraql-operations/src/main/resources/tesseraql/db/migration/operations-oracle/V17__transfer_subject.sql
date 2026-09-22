-- Who started a transfer (docs/job-inbox.md decision 1), Oracle variant: the same column and
-- owner index in Oracle types; the bootstrap tolerates ORA-01430 / ORA-00955 on a re-run, one
-- statement per object so a partial first run finishes on the next.

alter table tql_file_transfer add subject varchar2(256);
create index idx_tql_file_transfer_owner on tql_file_transfer (app_name, subject, created_at);
