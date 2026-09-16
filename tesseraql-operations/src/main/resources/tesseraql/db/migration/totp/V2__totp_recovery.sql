-- Recovery codes (docs/credential-lifecycle.md): pending_recovery holds the plain codes only
-- while the enrollment is unconfirmed (exactly like the pending secret in the same row);
-- confirmation hashes them into tql_totp_recovery (SHA-256 at rest, deleted on use) and
-- clears the column. Re-runnable on every boot: the column add through SqlScripts'
-- tolerated duplicate-column errors, the table through IF NOT EXISTS — a bare CREATE TABLE
-- is not tolerated on MySQL (1050) or H2, so the second boot of a password-login app on
-- MySQL failed here from 0.6.0 to 0.18.0 (docs/audit-low-leads.md slice 4). Oracle and SQL
-- Server have their own variants beside this one: on SQL Server the `timestamp` below is a
-- rowversion, which refuses the value the store writes.
alter table tql_user_totp add pending_recovery varchar(400);

create table if not exists tql_totp_recovery (
  tenant_id varchar(64) not null,
  subject varchar(255) not null,
  code_hash varchar(64) not null,
  created_at timestamp not null,
  primary key (tenant_id, subject, code_hash)
);
