-- The tenant a transfer was resolved for (docs/multi-tenancy.md), Oracle variant: the bootstrap
-- tolerates ORA-01430 on a re-run.

alter table tql_file_transfer add tenant_id varchar2(64);
