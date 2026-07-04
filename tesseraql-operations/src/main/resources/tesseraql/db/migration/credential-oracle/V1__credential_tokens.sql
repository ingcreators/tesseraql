-- One-time credential tokens (roadmap Phase 50), Oracle (23+) variant.
create table if not exists tql_credential_token (
  token_hash varchar2(64) primary key,
  login_id varchar2(255) not null,
  purpose varchar2(16) not null,
  expires_at timestamp not null,
  used_at timestamp,
  created_at timestamp not null
);
create index if not exists idx_tql_credential_token_login
  on tql_credential_token (login_id, purpose);
