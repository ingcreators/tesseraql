-- TesseraQL approval workflow (roadmap Phase 28 slice 2): the task inbox table. One row per open or
-- completed task a transition produces; tasks are managed regardless of where a workflow keeps its
-- state, so a single inbox spans every workflow. delegated_to and due_at are reserved for slice 3
-- (delegation, deadlines). No separate index keeps the DDL portable across PostgreSQL and MySQL.

create table if not exists tql_workflow_task (
  task_id varchar(64) primary key,
  doc_type varchar(128) not null,
  doc_id varchar(256) not null,
  state varchar(64) not null,
  assignee varchar(256),
  candidate_group varchar(256),
  status varchar(32) not null,
  created_at timestamp not null,
  completed_at timestamp,
  completed_by varchar(256),
  delegated_to varchar(256),
  due_at timestamp,
  tenant_id varchar(64)
);
