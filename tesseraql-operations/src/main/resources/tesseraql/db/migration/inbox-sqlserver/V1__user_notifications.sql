-- The per-user in-app inbox (roadmap Phase 49), SQL Server variant.
if object_id('tql_user_notification', 'U') is null
create table tql_user_notification (
  event_id nvarchar(64) primary key,
  tenant_id nvarchar(64) not null,
  subject nvarchar(255) not null,
  channel nvarchar(128) not null,
  source nvarchar(256) not null,
  title nvarchar(500) not null,
  body nvarchar(2000),
  created_at datetime2 not null,
  read_at datetime2
);
if not exists (select 1 from sys.indexes where name = 'idx_tql_user_notification_subject')
create index idx_tql_user_notification_subject on tql_user_notification (tenant_id, subject, created_at);
