-- The authorization server's store (docs/token-issuance.md decision 2), SQL Server
-- variant: codes, refresh tokens, clients and consents — never access tokens. Guarded so
-- the script stays re-runnable, as in V2/V3/V4.

if object_id('tql_oauth_client', 'U') is null
create table tql_oauth_client (
  client_id varchar(64) primary key,
  secret_hash varchar(64),
  redirect_uris varchar(max) not null,
  client_name varchar(200),
  metadata_json varchar(max),
  registered_at datetime2 not null,
  last_seen_at datetime2
);

if object_id('tql_oauth_code', 'U') is null
create table tql_oauth_code (
  code_hash varchar(64) primary key,
  client_id varchar(64) not null,
  subject varchar(64) not null,
  login_id varchar(200),
  resource_id varchar(500),
  acting_role varchar(200),
  code_challenge varchar(128),
  redirect_uri varchar(500),
  expires_at datetime2 not null
);

if object_id('tql_oauth_refresh', 'U') is null
create table tql_oauth_refresh (
  token_hash varchar(64) primary key,
  chain_id varchar(64) not null,
  client_id varchar(64) not null,
  subject varchar(64) not null,
  login_id varchar(200),
  resource_id varchar(500),
  acting_role varchar(200),
  issued_at datetime2 not null,
  expires_at datetime2 not null,
  rotated_at datetime2,
  revoked_at datetime2
);

if not exists (select 1 from sys.indexes where name = 'idx_tql_oauth_refresh_chain') create index idx_tql_oauth_refresh_chain on tql_oauth_refresh (chain_id);
if not exists (select 1 from sys.indexes where name = 'idx_tql_oauth_refresh_subject') create index idx_tql_oauth_refresh_subject on tql_oauth_refresh (subject);

if object_id('tql_oauth_consent', 'U') is null
create table tql_oauth_consent (
  client_id varchar(64) not null,
  subject varchar(64) not null,
  resource_id varchar(500) not null,
  acting_role varchar(200),
  granted_at datetime2 not null,
  primary key (client_id, subject, resource_id)
);
