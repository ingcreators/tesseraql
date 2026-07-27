-- Session metadata (docs/session-visibility.md), SQL Server variant: guarded single
-- statements keep the script re-runnable, as in V2.
if col_length('tql_session', 'session_handle') is null alter table tql_session add session_handle nvarchar(64);
if col_length('tql_session', 'user_agent') is null alter table tql_session add user_agent nvarchar(255);
if col_length('tql_session', 'remote_addr') is null alter table tql_session add remote_addr nvarchar(255);
if col_length('tql_session', 'last_seen_at') is null alter table tql_session add last_seen_at datetime2;
if not exists (select 1 from sys.indexes where name = 'idx_tql_session_handle') create index idx_tql_session_handle on tql_session (subject, session_handle);
