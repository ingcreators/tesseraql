-- TesseraQL poll-source exclusive consumption (docs/audit-hardening.md Decision 4), Oracle variant.

create table tql_poll_consumed (
  source_id varchar2(200) not null,
  file_key varchar2(512) not null,
  consumed_at timestamp not null,
  primary key (source_id, file_key)
);
