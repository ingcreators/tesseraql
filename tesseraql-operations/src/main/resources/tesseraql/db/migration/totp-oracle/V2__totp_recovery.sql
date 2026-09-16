-- Recovery codes (docs/credential-lifecycle.md), Oracle (23+) variant: varchar2 as in V1.
-- The bootstrap tolerates ORA-01430 (column already exists) and ORA-00955 (name already
-- used) on the re-run, so the plain statements stay idempotent.
alter table tql_user_totp add pending_recovery varchar2(400);

create table tql_totp_recovery (
  tenant_id varchar2(64) not null,
  subject varchar2(255) not null,
  code_hash varchar2(64) not null,
  created_at timestamp not null,
  primary key (tenant_id, subject, code_hash)
);
