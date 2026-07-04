-- TOTP enrollments (roadmap Phase 50 slice 3), Oracle (23+) variant.
create table if not exists tql_user_totp (
  tenant_id varchar2(64) not null,
  subject varchar2(255) not null,
  secret varchar2(64) not null,
  confirmed_at timestamp,
  last_used_step number(19) not null,
  created_at timestamp not null,
  primary key (tenant_id, subject)
);
