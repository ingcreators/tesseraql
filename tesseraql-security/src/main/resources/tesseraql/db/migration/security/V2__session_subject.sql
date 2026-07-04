-- The session's subject, powering the account surface's self-service session list
-- (roadmap Phase 48). Nullable: rows created before the upgrade are not listed and age
-- out at their expiry. Plain statements so every dialect's Flyway parses them; the
-- re-runnable ensureSchema bootstrap gets its idempotency from the tolerated
-- duplicate-column/-index errors in SqlScripts instead of IF NOT EXISTS (which MySQL
-- has no column/index form of).
alter table tql_session add column subject varchar(255);
create index idx_tql_session_subject on tql_session (subject);
