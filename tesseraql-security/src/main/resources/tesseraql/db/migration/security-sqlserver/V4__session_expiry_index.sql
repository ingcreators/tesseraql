-- Session expiry index (docs/framework-datasource.md), SQL Server variant: guarded so
-- the script stays re-runnable, as in V2/V3.
if not exists (select 1 from sys.indexes where name = 'idx_tql_session_expires') create index idx_tql_session_expires on tql_session (expires_at);
