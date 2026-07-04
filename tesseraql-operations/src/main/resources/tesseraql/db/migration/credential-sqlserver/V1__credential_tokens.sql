-- One-time credential tokens (roadmap Phase 50), SQL Server variant.
if object_id('tql_credential_token', 'U') is null
create table tql_credential_token (
  token_hash nvarchar(64) primary key,
  login_id nvarchar(255) not null,
  purpose nvarchar(16) not null,
  expires_at datetime2 not null,
  used_at datetime2,
  created_at datetime2 not null
);
if not exists (select 1 from sys.indexes where name = 'idx_tql_credential_token_login')
create index idx_tql_credential_token_login on tql_credential_token (login_id, purpose);
