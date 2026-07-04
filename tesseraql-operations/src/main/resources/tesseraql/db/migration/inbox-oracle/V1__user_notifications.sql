-- The per-user in-app inbox (roadmap Phase 49), Oracle (23+) variant.
create table if not exists tql_user_notification (
  event_id varchar2(64) primary key,
  tenant_id varchar2(64) not null,
  subject varchar2(255) not null,
  channel varchar2(128) not null,
  source varchar2(256) not null,
  title varchar2(500) not null,
  body varchar2(2000),
  created_at timestamp not null,
  read_at timestamp
);
create index if not exists idx_tql_user_notification_subject
  on tql_user_notification (tenant_id, subject, created_at);
