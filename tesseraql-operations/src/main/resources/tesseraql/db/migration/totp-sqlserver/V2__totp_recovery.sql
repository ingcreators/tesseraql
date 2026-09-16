-- Recovery codes (docs/credential-lifecycle.md), SQL Server variant: the common script's
-- `timestamp` is T-SQL's rowversion, which refuses an explicit value — every confirm
-- enabled the factor and then failed to store its codes (docs/audit-low-leads.md slice 4,
-- G42). datetime2 as in V1; the guards keep the script re-runnable, as the workflow-task
-- V2 column add does. A database bootstrapped before this variant existed keeps the
-- rowversion table: `create table` is tolerated, not rebuilt.
if col_length('tql_user_totp', 'pending_recovery') is null
alter table tql_user_totp add pending_recovery nvarchar(400);

if object_id('tql_totp_recovery', 'U') is null
create table tql_totp_recovery (
  tenant_id nvarchar(64) not null,
  subject nvarchar(255) not null,
  code_hash nvarchar(64) not null,
  created_at datetime2 not null,
  primary key (tenant_id, subject, code_hash)
);
