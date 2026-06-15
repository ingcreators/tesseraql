-- TesseraQL approval workflow (roadmap Phase 28 slice 1), SQL Server variant.

if object_id('tql_workflow_instance', 'U') is null
create table tql_workflow_instance (
  instance_id varchar(320) primary key,
  doc_type varchar(128) not null,
  doc_id varchar(256) not null,
  current_state varchar(64) not null,
  tenant_id varchar(64),
  updated_at datetime2 not null,
  unique (doc_type, doc_id)
);

if object_id('tql_workflow_history', 'U') is null
create table tql_workflow_history (
  history_id varchar(64) primary key,
  instance_id varchar(320) not null,
  doc_type varchar(128) not null,
  doc_id varchar(256) not null,
  transition varchar(64) not null,
  from_state varchar(64),
  to_state varchar(64) not null,
  actor varchar(256),
  acted_at datetime2 not null,
  note varchar(2000)
);
