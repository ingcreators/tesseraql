-- Bulk action reports (docs/bulk-report.md decision 6), Oracle (23+) variant.
create table if not exists tql_bulk_report (
  handle varchar2(64) not null,
  subject varchar2(255) not null,
  payload clob not null,
  expires_at timestamp not null,
  primary key (handle)
);
