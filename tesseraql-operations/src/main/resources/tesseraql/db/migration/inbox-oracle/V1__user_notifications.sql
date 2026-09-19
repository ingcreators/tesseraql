-- The per-user in-app inbox (roadmap Phase 49), Oracle (23+) variant.
-- The notifier keeps a title within 500 characters and a body within 2,000 (as PostgreSQL's
-- varchar and SQL Server's nvarchar count them): the title in CHAR semantics, the body a CLOB,
-- since varchar2 caps at 4,000 BYTES whatever the semantics and 2,000 CJK characters are 6,000.
-- Under the default BYTE semantics a 167-character CJK title was ORA-12899 and the notification
-- dead-lettered (docs/audit-low-leads.md unfiled 56). A table created before this keeps its
-- byte columns.
create table if not exists tql_user_notification (
  event_id varchar2(64) primary key,
  tenant_id varchar2(64) not null,
  subject varchar2(255) not null,
  channel varchar2(128) not null,
  source varchar2(256) not null,
  title varchar2(500 char) not null,
  body clob,
  created_at timestamp not null,
  read_at timestamp
);
create index if not exists idx_tql_user_notification_subject
  on tql_user_notification (tenant_id, subject, created_at);
