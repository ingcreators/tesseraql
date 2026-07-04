-- The per-user in-app inbox (roadmap Phase 49): one row per delivered notification,
-- keyed by the outbox event id (the at-least-once dedupe). Applied idempotently by
-- JdbcInboxStore.ensureSchema; deliberately OUTSIDE the Flyway component set (the
-- tql_user_preference pattern), so no second owner ever races this script.
create table if not exists tql_user_notification (
  event_id varchar(64) primary key,
  tenant_id varchar(64) not null,
  subject varchar(255) not null,
  channel varchar(128) not null,
  source varchar(256) not null,
  title varchar(500) not null,
  body varchar(2000),
  created_at timestamp not null,
  read_at timestamp
);
create index if not exists idx_tql_user_notification_subject
  on tql_user_notification (tenant_id, subject, created_at);
