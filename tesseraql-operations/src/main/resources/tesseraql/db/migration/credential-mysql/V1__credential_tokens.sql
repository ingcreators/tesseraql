-- One-time credential tokens (roadmap Phase 50): password resets and invitations. The row
-- holds the SHA-256 of the token, never the token. Applied idempotently by
-- JdbcCredentialTokenStore.ensureSchema; deliberately OUTSIDE the Flyway component set.
-- MySQL variant: no IF NOT EXISTS on CREATE INDEX (MySQL 8 has none; the second
-- boot's duplicate-key error 1061 is tolerated by SqlScripts.applyForVendor).
-- Before it, the credential-token schema failed on MySQL (docs/audit-low-leads.md unfiled 77).
create table if not exists tql_credential_token (
  token_hash varchar(64) primary key,
  login_id varchar(255) not null,
  purpose varchar(16) not null,
  expires_at timestamp not null,
  used_at timestamp,
  created_at timestamp not null
);
create index idx_tql_credential_token_login
  on tql_credential_token (login_id, purpose);
