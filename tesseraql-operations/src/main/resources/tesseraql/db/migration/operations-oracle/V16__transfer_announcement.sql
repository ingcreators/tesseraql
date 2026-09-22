-- What a transfer's follow-up announces when it commits (docs/list-export.md), Oracle variant:
-- the bootstrap tolerates ORA-01430 on a re-run, one statement per column so a partial first
-- run finishes on the next.

alter table tql_file_transfer add emit_json clob;
alter table tql_file_transfer add invalidates_json clob;
alter table tql_file_transfer add emit_tenant_id varchar2(64);
