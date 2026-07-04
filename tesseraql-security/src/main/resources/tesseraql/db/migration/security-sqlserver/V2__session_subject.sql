-- Roadmap Phase 48 session subject, SQL Server variant: guarded single statements keep
-- the script re-runnable (no IF NOT EXISTS for columns).
if col_length('tql_session', 'subject') is null alter table tql_session add subject nvarchar(255);
if not exists (select 1 from sys.indexes where name = 'idx_tql_session_subject') create index idx_tql_session_subject on tql_session (subject);
