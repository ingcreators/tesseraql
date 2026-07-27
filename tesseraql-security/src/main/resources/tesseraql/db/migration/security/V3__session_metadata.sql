-- Session metadata (docs/session-visibility.md): a public handle for row actions (never
-- the cookie id), the client facts captured at login, and the last-seen instant feeding
-- the optional idle timeout. Nullable: rows created before the upgrade render dashes and
-- age out at their expiry. Plain statements, idempotency via the bootstrap's tolerated
-- duplicate-column/-index errors, as in V2.
alter table tql_session add column session_handle varchar(64);
alter table tql_session add column user_agent varchar(255);
alter table tql_session add column remote_addr varchar(255);
alter table tql_session add column last_seen_at timestamp;
create index idx_tql_session_handle on tql_session (subject, session_handle);
