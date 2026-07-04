-- Per-user pins and recents (roadmap Phase 51), SQL Server variant. The PK must stay
-- under the 900-byte clustered limit via the href_hash key column.
if object_id('tql_user_shortcut', 'U') is null
create table tql_user_shortcut (
  tenant_id nvarchar(64) not null,
  subject nvarchar(255) not null,
  kind nvarchar(16) not null,
  href_hash nvarchar(64) not null,
  href nvarchar(1000) not null,
  label nvarchar(200) not null,
  touched_at datetime2 not null,
  primary key (tenant_id, subject, kind, href_hash)
);
