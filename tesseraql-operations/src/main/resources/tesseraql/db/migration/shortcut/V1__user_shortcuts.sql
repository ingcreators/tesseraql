-- Per-user pins and recents (roadmap Phase 51): two capped lists of {label, href} per
-- subject. Applied idempotently by JdbcShortcutStore.ensureSchema; deliberately OUTSIDE
-- the Flyway component set.
create table if not exists tql_user_shortcut (
  tenant_id varchar(64) not null,
  subject varchar(255) not null,
  kind varchar(16) not null,
  href_hash varchar(64) not null,
  href varchar(1000) not null,
  label varchar(200) not null,
  touched_at timestamp not null,
  primary key (tenant_id, subject, kind, href_hash)
);
