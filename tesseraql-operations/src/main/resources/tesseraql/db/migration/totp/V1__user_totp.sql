-- TOTP enrollments (roadmap Phase 50 slice 3): one optional second factor per subject.
-- last_used_step is the replay guard's compare-and-set floor. Applied idempotently by
-- JdbcTotpStore.ensureSchema; deliberately OUTSIDE the Flyway component set.
create table if not exists tql_user_totp (
  tenant_id varchar(64) not null,
  subject varchar(255) not null,
  secret varchar(64) not null,
  confirmed_at timestamp,
  last_used_step bigint not null,
  created_at timestamp not null,
  primary key (tenant_id, subject)
);
