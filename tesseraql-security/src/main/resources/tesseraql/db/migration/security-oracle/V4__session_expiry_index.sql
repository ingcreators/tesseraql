-- Session expiry index (docs/framework-datasource.md), Oracle variant: the bootstrap
-- tolerates ORA-00955 on the re-run, as in V2/V3.
create index idx_tql_session_expires on tql_session (expires_at);
