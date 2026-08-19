-- The authorization server's store (docs/token-issuance.md decision 2), Oracle (23+)
-- variant: codes, refresh tokens, clients and consents — never access tokens. The
-- bootstrap tolerates ORA-00955 on the re-run, as in V2/V3/V4.

create table tql_oauth_client (
  client_id varchar2(64) primary key,
  secret_hash varchar2(64),
  redirect_uris clob not null,
  client_name varchar2(200),
  metadata_json clob,
  registered_at timestamp not null,
  last_seen_at timestamp
);

create table tql_oauth_code (
  code_hash varchar2(64) primary key,
  client_id varchar2(64) not null,
  subject varchar2(64) not null,
  login_id varchar2(200),
  resource_id varchar2(500),
  acting_role varchar2(200),
  code_challenge varchar2(128),
  redirect_uri varchar2(500),
  expires_at timestamp not null
);

create table tql_oauth_refresh (
  token_hash varchar2(64) primary key,
  chain_id varchar2(64) not null,
  client_id varchar2(64) not null,
  subject varchar2(64) not null,
  login_id varchar2(200),
  resource_id varchar2(500),
  acting_role varchar2(200),
  issued_at timestamp not null,
  expires_at timestamp not null,
  rotated_at timestamp,
  revoked_at timestamp
);

create index idx_tql_oauth_refresh_chain on tql_oauth_refresh (chain_id);
create index idx_tql_oauth_refresh_subject on tql_oauth_refresh (subject);

create table tql_oauth_consent (
  client_id varchar2(64) not null,
  subject varchar2(64) not null,
  resource_id varchar2(500) not null,
  acting_role varchar2(200),
  granted_at timestamp not null,
  primary key (client_id, subject, resource_id)
);
