-- The authorization server's signing keys (docs/token-issuance.md decision 3), SQL Server
-- variant. Guarded so the script stays re-runnable, as in the earlier versions.

if object_id('tql_oauth_signing_key', 'U') is null
create table tql_oauth_signing_key (
  kid varchar(64) primary key,
  private_key varchar(max) not null,
  public_key varchar(max) not null,
  created_at datetime2 not null,
  retired_at datetime2
);
