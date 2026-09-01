-- Bulk action reports (docs/bulk-report.md decision 6): the outcome report a bulk
-- endpoint's browser leg stores for the redirect round trip. Subject-scoped, TTL-bounded,
-- swept opportunistically on write. Applied idempotently by
-- JdbcBulkReportStore.ensureSchema; deliberately OUTSIDE the Flyway component set.
create table if not exists tql_bulk_report (
  handle varchar(64) not null,
  subject varchar(255) not null,
  payload text not null,
  expires_at timestamp not null,
  primary key (handle)
);
