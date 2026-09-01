-- Bulk action reports (docs/bulk-report.md decision 6), SQL Server variant.
if object_id('tql_bulk_report', 'U') is null
create table tql_bulk_report (
  handle nvarchar(64) not null,
  subject nvarchar(255) not null,
  payload nvarchar(max) not null,
  expires_at datetime2 not null,
  primary key (handle)
);
