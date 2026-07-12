-- Recovery codes (docs/credential-lifecycle.md): pending_recovery holds the plain codes only
-- while the enrollment is unconfirmed (exactly like the pending secret in the same row);
-- confirmation hashes them into tql_totp_recovery (SHA-256 at rest, deleted on use) and
-- clears the column. Applied idempotently via SqlScripts' already-exists tolerance.
alter table tql_user_totp add pending_recovery varchar(400);

create table tql_totp_recovery (
  tenant_id varchar(64) not null,
  subject varchar(255) not null,
  code_hash varchar(64) not null,
  created_at timestamp not null,
  primary key (tenant_id, subject, code_hash)
);
