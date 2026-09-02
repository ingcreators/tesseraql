-- Parked import batches (docs/csv-import.md decision 2), SQL Server variant: guarded so the
-- script stays re-runnable, as the V11 indexes and the session V4 index do.

if object_id('tql_import_batch', 'U') is null
create table tql_import_batch (
  batch_id varchar(64) primary key,
  route_id varchar(256) not null,
  app_name varchar(256) not null,
  subject varchar(256) not null,
  format varchar(32) not null,
  spool_id varchar(64),
  spool_uri varchar(1000),
  read_spec_json varchar(max),
  contract_json varchar(max),
  report_json varchar(max),
  row_count bigint not null default 0,
  ready_count bigint not null default 0,
  rejected_count bigint not null default 0,
  status varchar(32) not null,
  transfer_id varchar(64),
  claimed_at datetime2,
  expires_at datetime2 not null,
  created_at datetime2 not null
);

if not exists (select 1 from sys.indexes where name = 'idx_tql_import_batch_expiry') create index idx_tql_import_batch_expiry on tql_import_batch (expires_at);
if not exists (select 1 from sys.indexes where name = 'idx_tql_import_batch_owner') create index idx_tql_import_batch_owner on tql_import_batch (app_name, subject);
