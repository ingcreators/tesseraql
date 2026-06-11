-- TesseraQL framework tables: shared sessions (design ch. 11.2), Oracle (23+) variant.
create table tql_session (
  session_id varchar2(64) primary key,
  principal_json clob not null,
  csrf_token varchar2(64) not null,
  created_at timestamp not null,
  expires_at timestamp not null
);
