-- The login-path prune (delete ... where expires_at < ?) and the cross-subject listing
-- scanned (docs/framework-datasource.md); on MySQL an unindexed DELETE also locks more
-- than it should. Idempotency via the bootstrap's tolerated duplicate-index error, as
-- in V2/V3.
create index idx_tql_session_expires on tql_session (expires_at);
