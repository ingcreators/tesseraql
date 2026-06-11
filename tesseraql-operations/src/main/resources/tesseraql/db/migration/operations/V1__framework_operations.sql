-- TesseraQL framework tables: batch executions, job claims, idempotency and the
-- transactional outbox (design ch. 26.3, 39).
-- Applied idempotently by the JDBC stores and Flyway-managed by the runtime
-- (history table tql_schema_history__operations), so it must stay re-runnable.

create table if not exists tql_job_execution (
  job_execution_id varchar(64) primary key,
  job_id varchar(256) not null,
  app_name varchar(256) not null,
  status varchar(32) not null,
  trigger_type varchar(32),
  start_time timestamp not null,
  end_time timestamp,
  duration_ms bigint,
  exit_message varchar(2000),
  created_at timestamp not null
);

create table if not exists tql_step_execution (
  step_execution_id varchar(64) primary key,
  job_execution_id varchar(64) not null,
  step_id varchar(256) not null,
  status varchar(32) not null,
  start_time timestamp not null,
  end_time timestamp,
  duration_ms bigint,
  affected_rows integer,
  error_message varchar(2000)
);

create table if not exists tql_job_claim (
  job_id varchar(256) not null,
  fire_time timestamp not null,
  claimed_at timestamp not null,
  primary key (job_id, fire_time)
);

create table if not exists tql_idempotency_record (
  scope varchar(256) not null,
  idempotency_key varchar(512) not null,
  request_hash varchar(128) not null,
  status varchar(32) not null,
  response_status integer,
  response_body text,
  response_content_type varchar(128),
  expires_at timestamp not null,
  created_at timestamp not null,
  primary key (scope, idempotency_key)
);

create table if not exists tql_file_transfer (
  transfer_id varchar(64) primary key,
  route_id varchar(256) not null,
  app_name varchar(256) not null,
  direction varchar(16) not null,
  format varchar(32) not null,
  filename varchar(500),
  spool_uri varchar(1000),
  row_count bigint not null default 0,
  error_json text,
  after_timing varchar(16),
  after_sql_file varchar(1000),
  params_json text,
  downloaded_at timestamp,
  created_at timestamp not null
);

create table if not exists tql_outbox_event (
  event_id varchar(64) primary key,
  aggregate_type varchar(128),
  aggregate_id varchar(256),
  event_type varchar(256) not null,
  payload_json text,
  status varchar(32) not null,
  attempts integer not null default 0,
  last_error varchar(2000),
  created_at timestamp not null,
  sent_at timestamp,
  claimed_at timestamp,
  app_name varchar(256) not null
);
