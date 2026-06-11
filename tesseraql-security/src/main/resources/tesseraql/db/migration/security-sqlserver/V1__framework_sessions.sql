-- TesseraQL framework tables: shared sessions (design ch. 11.2), SQL Server variant.
if object_id('tql_session', 'U') is null
create table tql_session (
  session_id varchar(64) primary key,
  principal_json varchar(max) not null,
  csrf_token varchar(64) not null,
  created_at datetime2 not null,
  expires_at datetime2 not null
);
