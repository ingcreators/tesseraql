-- Parked import batches (docs/csv-import.md decision 2), Oracle variant: same shape as the
-- common script in Oracle types; the bootstrap tolerates ORA-00955 / ORA-01430 on a re-run.

create table tql_import_batch (
  batch_id varchar2(64) primary key,
  route_id varchar2(256) not null,
  app_name varchar2(256) not null,
  subject varchar2(256) not null,
  format varchar2(32) not null,
  spool_id varchar2(64),
  spool_uri varchar2(1000),
  read_spec_json clob,
  contract_json clob,
  report_json clob,
  row_count number(19) default 0 not null,
  ready_count number(19) default 0 not null,
  rejected_count number(19) default 0 not null,
  status varchar2(32) not null,
  transfer_id varchar2(64),
  claimed_at timestamp,
  expires_at timestamp not null,
  created_at timestamp not null
);

create index idx_tql_import_batch_expiry on tql_import_batch (expires_at);
create index idx_tql_import_batch_owner on tql_import_batch (app_name, subject);
