-- TesseraQL approval-workflow task inbox (roadmap Phase 28 slice 2), Oracle variant. The bootstrap
-- tolerates ORA-00955 (name already used), so plain CREATE is idempotent on re-run.

create table tql_workflow_task (
  task_id varchar2(64) primary key,
  doc_type varchar2(128) not null,
  doc_id varchar2(256) not null,
  state varchar2(64) not null,
  assignee varchar2(256),
  candidate_group varchar2(256),
  status varchar2(32) not null,
  created_at timestamp not null,
  completed_at timestamp,
  completed_by varchar2(256),
  delegated_to varchar2(256),
  due_at timestamp,
  tenant_id varchar2(64)
);
