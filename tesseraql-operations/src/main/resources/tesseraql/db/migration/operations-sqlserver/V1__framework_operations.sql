-- TesseraQL framework tables (design ch. 26.3, 28, 39), SQL Server variant.

if object_id('tql_job_execution', 'U') is null
create table tql_job_execution (
  job_execution_id varchar(64) primary key,
  job_id varchar(256) not null,
  app_name varchar(256) not null,
  status varchar(32) not null,
  trigger_type varchar(32),
  start_time datetime2 not null,
  end_time datetime2,
  duration_ms bigint,
  exit_message varchar(2000),
  created_at datetime2 not null
);

if object_id('tql_step_execution', 'U') is null
create table tql_step_execution (
  step_execution_id varchar(64) primary key,
  job_execution_id varchar(64) not null,
  step_id varchar(256) not null,
  status varchar(32) not null,
  start_time datetime2 not null,
  end_time datetime2,
  duration_ms bigint,
  affected_rows int,
  error_message varchar(2000)
);

if object_id('tql_job_claim', 'U') is null
create table tql_job_claim (
  job_id varchar(256) not null,
  fire_time datetime2 not null,
  claimed_at datetime2 not null,
  primary key (job_id, fire_time)
);

if object_id('tql_idempotency_record', 'U') is null
create table tql_idempotency_record (
  scope varchar(256) not null,
  idempotency_key varchar(512) not null,
  request_hash varchar(128) not null,
  status varchar(32) not null,
  response_status int,
  response_body varchar(max),
  response_content_type varchar(128),
  expires_at datetime2 not null,
  created_at datetime2 not null,
  primary key (scope, idempotency_key)
);

if object_id('tql_file_transfer', 'U') is null
create table tql_file_transfer (
  transfer_id varchar(64) primary key,
  route_id varchar(256) not null,
  app_name varchar(256) not null,
  direction varchar(16) not null,
  format varchar(32) not null,
  filename varchar(500),
  spool_uri varchar(1000),
  row_count bigint not null default 0,
  error_json varchar(max),
  after_timing varchar(16),
  after_sql_file varchar(1000),
  params_json varchar(max),
  downloaded_at datetime2,
  created_at datetime2 not null
);

if object_id('tql_outbox_event', 'U') is null
create table tql_outbox_event (
  event_id varchar(64) primary key,
  aggregate_type varchar(128),
  aggregate_id varchar(256),
  event_type varchar(256) not null,
  payload_json varchar(max),
  status varchar(32) not null,
  attempts int not null default 0,
  last_error varchar(2000),
  created_at datetime2 not null,
  sent_at datetime2,
  claimed_at datetime2,
  app_name varchar(256) not null
);
