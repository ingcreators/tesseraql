-- Per-user preferences for the account surface (roadmap Phase 48), Oracle (23+) variant.
create table if not exists tql_user_preference (
  tenant_id varchar2(64) not null,
  subject varchar2(255) not null,
  pref_key varchar2(128) not null,
  pref_value varchar2(2000) not null,
  updated_at timestamp not null,
  primary key (tenant_id, subject, pref_key)
);
