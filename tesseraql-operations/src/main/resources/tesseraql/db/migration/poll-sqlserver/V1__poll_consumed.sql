-- TesseraQL poll-source exclusive consumption (docs/audit-hardening.md Decision 4), SQL Server
-- variant.

if object_id('tql_poll_consumed', 'U') is null
create table tql_poll_consumed (
  source_id varchar(200) not null,
  file_key varchar(450) not null,
  consumed_at datetime2 not null,
  primary key (source_id, file_key)
);
