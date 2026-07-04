-- Per-user pins and recents (roadmap Phase 51), Oracle (23+) variant.
create table if not exists tql_user_shortcut (
  tenant_id varchar2(64) not null,
  subject varchar2(255) not null,
  kind varchar2(16) not null,
  href_hash varchar2(64) not null,
  href varchar2(1000) not null,
  label varchar2(200) not null,
  touched_at timestamp not null,
  primary key (tenant_id, subject, kind, href_hash)
);
