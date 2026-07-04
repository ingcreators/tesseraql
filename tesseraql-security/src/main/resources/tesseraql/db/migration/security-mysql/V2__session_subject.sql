-- Roadmap Phase 48 session subject, MySQL variant: MySQL 8 has no IF NOT EXISTS for
-- columns or indexes, so the bootstrap tolerates the duplicate-column/-key errors
-- (1060/1061) the way Oracle's tolerates ORA-00955.
alter table tql_session add column subject varchar(255);
create index idx_tql_session_subject on tql_session (subject);
