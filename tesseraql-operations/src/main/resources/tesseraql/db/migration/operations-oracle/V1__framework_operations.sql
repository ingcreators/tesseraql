-- TesseraQL framework tables (design ch. 26.3, 28, 39), Oracle (23+) variant.

create table tql_job_execution (
  job_execution_id varchar2(64) primary key,
  job_id varchar2(256) not null,
  app_name varchar2(256) not null,
  status varchar2(32) not null,
  trigger_type varchar2(32),
  start_time timestamp not null,
  end_time timestamp,
  duration_ms number(19),
  exit_message varchar2(2000),
  created_at timestamp not null
);

create table tql_step_execution (
  step_execution_id varchar2(64) primary key,
  job_execution_id varchar2(64) not null,
  step_id varchar2(256) not null,
  status varchar2(32) not null,
  start_time timestamp not null,
  end_time timestamp,
  duration_ms number(19),
  affected_rows number(10),
  error_message varchar2(2000)
);

create table tql_job_claim (
  job_id varchar2(256) not null,
  fire_time timestamp not null,
  claimed_at timestamp not null,
  primary key (job_id, fire_time)
);

create table tql_idempotency_record (
  scope varchar2(256) not null,
  idempotency_key varchar2(512) not null,
  request_hash varchar2(128) not null,
  status varchar2(32) not null,
  response_status number(10),
  response_body clob,
  response_content_type varchar2(128),
  expires_at timestamp not null,
  created_at timestamp not null,
  primary key (scope, idempotency_key)
);

create table tql_file_transfer (
  transfer_id varchar2(64) primary key,
  route_id varchar2(256) not null,
  app_name varchar2(256) not null,
  direction varchar2(16) not null,
  format varchar2(32) not null,
  filename varchar2(500),
  spool_uri varchar2(1000),
  row_count number(19) default 0 not null,
  error_json clob,
  after_timing varchar2(16),
  after_sql_file varchar2(1000),
  params_json clob,
  downloaded_at timestamp,
  created_at timestamp not null
);

create table tql_outbox_event (
  event_id varchar2(64) primary key,
  aggregate_type varchar2(128),
  aggregate_id varchar2(256),
  event_type varchar2(256) not null,
  payload_json clob,
  status varchar2(32) not null,
  attempts number(10) default 0 not null,
  last_error varchar2(2000),
  created_at timestamp not null,
  sent_at timestamp,
  claimed_at timestamp,
  app_name varchar2(256) not null
);
