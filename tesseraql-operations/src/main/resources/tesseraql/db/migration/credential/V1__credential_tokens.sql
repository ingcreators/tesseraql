-- One-time credential tokens (roadmap Phase 50): password resets and invitations. The row
-- holds the SHA-256 of the token, never the token. Applied idempotently by
-- JdbcCredentialTokenStore.ensureSchema; deliberately OUTSIDE the Flyway component set.
create table if not exists tql_credential_token (
  token_hash varchar(64) primary key,
  login_id varchar(255) not null,
  purpose varchar(16) not null,
  expires_at timestamp not null,
  used_at timestamp,
  created_at timestamp not null
);
create index if not exists idx_tql_credential_token_login
  on tql_credential_token (login_id, purpose);
