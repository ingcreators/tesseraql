-- Per-user preferences for the account surface (roadmap Phase 48): namespaced key/value
-- pairs per tenant + subject. Applied idempotently by JdbcPreferenceStore.ensureSchema.
create table if not exists tql_user_preference (
  tenant_id varchar(64) not null,
  subject varchar(255) not null,
  pref_key varchar(128) not null,
  pref_value varchar(2000) not null,
  updated_at timestamp not null,
  primary key (tenant_id, subject, pref_key)
);
