-- Standing absence rules (roadmap Phase 52), Oracle (23+) variant.
create table if not exists tql_workflow_delegation (
  tenant_id varchar2(64) not null,
  subject varchar2(255) not null,
  delegate_subject varchar2(255) not null,
  starts_at timestamp not null,
  ends_at timestamp not null,
  created_at timestamp not null,
  primary key (tenant_id, subject)
);
