-- TesseraQL approval workflow (roadmap Phase 28 slice 1), Oracle variant. The bootstrap tolerates
-- ORA-00955 (name already used), so plain CREATE is idempotent on re-run.

create table tql_workflow_instance (
  instance_id varchar2(320) primary key,
  doc_type varchar2(128) not null,
  doc_id varchar2(256) not null,
  current_state varchar2(64) not null,
  tenant_id varchar2(64),
  updated_at timestamp not null,
  unique (doc_type, doc_id)
);

create table tql_workflow_history (
  history_id varchar2(64) primary key,
  instance_id varchar2(320) not null,
  doc_type varchar2(128) not null,
  doc_id varchar2(256) not null,
  transition varchar2(64) not null,
  from_state varchar2(64),
  to_state varchar2(64) not null,
  actor varchar2(256),
  acted_at timestamp not null,
  note varchar2(2000)
);
