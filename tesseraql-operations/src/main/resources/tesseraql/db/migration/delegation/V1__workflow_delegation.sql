-- Standing absence rules (roadmap Phase 52): one window and one delegate per subject,
-- written only by that subject. Applied idempotently by JdbcDelegationStore.ensureSchema;
-- deliberately OUTSIDE the Flyway component set.
create table if not exists tql_workflow_delegation (
  tenant_id varchar(64) not null,
  subject varchar(255) not null,
  delegate_subject varchar(255) not null,
  starts_at timestamp not null,
  ends_at timestamp not null,
  created_at timestamp not null,
  primary key (tenant_id, subject)
);
