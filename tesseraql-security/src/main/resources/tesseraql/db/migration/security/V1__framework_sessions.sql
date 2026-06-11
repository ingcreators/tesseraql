-- TesseraQL framework tables: shared sessions (design ch. 11.2).
-- Applied idempotently by JdbcSessionStore and Flyway-managed by the runtime
-- (history table tql_schema_history__security), so it must stay re-runnable.

create table if not exists tql_session (
  session_id varchar(64) primary key,
  principal_json text not null,
  csrf_token varchar(64) not null,
  created_at timestamp not null,
  expires_at timestamp not null
);
