-- Roadmap Phase 48 session subject, Oracle variant: plain ADD, with the bootstrap
-- tolerating ORA-01430 (column already exists) alongside the established ORA-00955.
alter table tql_session add (subject varchar2(255));
create index idx_tql_session_subject on tql_session (subject);
