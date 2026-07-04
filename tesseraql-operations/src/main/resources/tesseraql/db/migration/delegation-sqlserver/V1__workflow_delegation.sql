-- Standing absence rules (roadmap Phase 52), SQL Server variant.
if object_id('tql_workflow_delegation', 'U') is null
create table tql_workflow_delegation (
  tenant_id nvarchar(64) not null,
  subject nvarchar(255) not null,
  delegate_subject nvarchar(255) not null,
  starts_at datetime2 not null,
  ends_at datetime2 not null,
  created_at datetime2 not null,
  primary key (tenant_id, subject)
);
