-- TesseraQL poll-source exclusive consumption (docs/audit-hardening.md Decision 4): the first
-- replica to insert a file's key consumes it; every other replica's insert hits the primary key
-- and skips. The read lock the consumer already carries is a write-stability check, and on
-- sftp/ftps the remote strategy takes no lock at all, so without this every replica imports
-- every file.

create table if not exists tql_poll_consumed (
  source_id varchar(200) not null,
  file_key varchar(512) not null,
  consumed_at timestamp not null,
  primary key (source_id, file_key)
);
