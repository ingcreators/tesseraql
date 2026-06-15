-- TesseraQL approval workflow (roadmap Phase 28 slice 1): the managed state machine tables.
-- tql_workflow_instance holds one row per document under workflow (its current state); the inline
-- unique (doc_type, doc_id) keeps a document to a single instance and stays portable across
-- PostgreSQL and MySQL (neither needs a separate CREATE INDEX). tql_workflow_history is the
-- append-only audit trail: one row per fired transition. A transition advances the instance and
-- appends history on the command's own connection, so both commit atomically with the business
-- write. The task table (assignee inbox) arrives in slice 2.

create table if not exists tql_workflow_instance (
  instance_id varchar(320) primary key,
  doc_type varchar(128) not null,
  doc_id varchar(256) not null,
  current_state varchar(64) not null,
  tenant_id varchar(64),
  updated_at timestamp not null,
  unique (doc_type, doc_id)
);

create table if not exists tql_workflow_history (
  history_id varchar(64) primary key,
  instance_id varchar(320) not null,
  doc_type varchar(128) not null,
  doc_id varchar(256) not null,
  transition varchar(64) not null,
  from_state varchar(64),
  to_state varchar(64) not null,
  actor varchar(256),
  acted_at timestamp not null,
  note varchar(2000)
);
