-- TOTP enrollments (roadmap Phase 50 slice 3), SQL Server variant.
if object_id('tql_user_totp', 'U') is null
create table tql_user_totp (
  tenant_id nvarchar(64) not null,
  subject nvarchar(255) not null,
  secret nvarchar(64) not null,
  confirmed_at datetime2,
  last_used_step bigint not null,
  created_at datetime2 not null,
  primary key (tenant_id, subject)
);
