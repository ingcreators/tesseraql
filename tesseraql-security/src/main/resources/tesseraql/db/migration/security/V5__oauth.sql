-- The authorization server's store (docs/token-issuance.md decision 2): codes, refresh
-- tokens, clients and consents — and deliberately not access tokens, which are stateless.
-- Codes and refresh tokens are bearer secrets, so only their SHA-256 hex lands here; a
-- rotated or revoked refresh row keeps its timestamps so reuse detection can tell the
-- difference between "unknown" and "already spent".

create table if not exists tql_oauth_client (
  client_id varchar(64) primary key,
  secret_hash varchar(64),
  redirect_uris text not null,
  client_name varchar(200),
  metadata_json text,
  registered_at timestamp not null,
  last_seen_at timestamp
);

create table if not exists tql_oauth_code (
  code_hash varchar(64) primary key,
  client_id varchar(64) not null,
  subject varchar(64) not null,
  login_id varchar(200),
  resource_id varchar(500),
  acting_role varchar(200),
  code_challenge varchar(128),
  redirect_uri varchar(500),
  expires_at timestamp not null
);

create table if not exists tql_oauth_refresh (
  token_hash varchar(64) primary key,
  chain_id varchar(64) not null,
  client_id varchar(64) not null,
  subject varchar(64) not null,
  login_id varchar(200),
  resource_id varchar(500),
  acting_role varchar(200),
  issued_at timestamp not null,
  expires_at timestamp not null,
  rotated_at timestamp,
  revoked_at timestamp
);

create index idx_tql_oauth_refresh_chain on tql_oauth_refresh (chain_id);
create index idx_tql_oauth_refresh_subject on tql_oauth_refresh (subject);

create table if not exists tql_oauth_consent (
  client_id varchar(64) not null,
  subject varchar(64) not null,
  resource_id varchar(500) not null,
  acting_role varchar(200),
  granted_at timestamp not null,
  primary key (client_id, subject, resource_id)
);
