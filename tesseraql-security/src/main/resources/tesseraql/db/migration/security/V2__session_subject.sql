-- The session's subject, powering the account surface's self-service session list
-- (roadmap Phase 48). Nullable: rows created before the upgrade are not listed and age
-- out at their expiry. Applied idempotently by JdbcSessionStore.ensureSchema on every
-- boot, so the statements must stay re-runnable.
alter table tql_session add column if not exists subject varchar(255);
create index if not exists idx_tql_session_subject on tql_session (subject);
