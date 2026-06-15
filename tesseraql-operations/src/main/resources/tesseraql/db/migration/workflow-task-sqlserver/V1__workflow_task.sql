-- TesseraQL approval-workflow task inbox (roadmap Phase 28 slice 2), SQL Server variant.

if object_id('tql_workflow_task', 'U') is null
create table tql_workflow_task (
  task_id varchar(64) primary key,
  doc_type varchar(128) not null,
  doc_id varchar(256) not null,
  state varchar(64) not null,
  assignee varchar(256),
  candidate_group varchar(256),
  status varchar(32) not null,
  created_at datetime2 not null,
  completed_at datetime2,
  completed_by varchar(256),
  delegated_to varchar(256),
  due_at datetime2,
  tenant_id varchar(64)
);
