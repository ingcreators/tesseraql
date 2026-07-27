-- Session metadata (docs/session-visibility.md), Oracle variant: plain ADDs, the
-- bootstrap tolerating ORA-01430 alongside ORA-00955 as in V2.
alter table tql_session add (session_handle varchar2(64));
alter table tql_session add (user_agent varchar2(255));
alter table tql_session add (remote_addr varchar2(255));
alter table tql_session add (last_seen_at timestamp);
create index idx_tql_session_handle on tql_session (subject, session_handle);
