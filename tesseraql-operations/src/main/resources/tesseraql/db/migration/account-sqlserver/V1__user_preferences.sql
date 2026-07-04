-- Per-user preferences for the account surface (roadmap Phase 48), SQL Server variant.
-- The composite key stays under the 900-byte clustered index limit: 447 nvarchar chars.
if object_id('tql_user_preference', 'U') is null
create table tql_user_preference (
  tenant_id nvarchar(64) not null,
  subject nvarchar(255) not null,
  pref_key nvarchar(128) not null,
  pref_value nvarchar(2000) not null,
  updated_at datetime2 not null,
  primary key (tenant_id, subject, pref_key)
);
