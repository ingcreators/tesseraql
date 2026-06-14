-- TesseraQL messaging channel event log (roadmap Phase 27), SQL Server variant.

if object_id('tql_event', 'U') is null
create table tql_event (
  event_id varchar(64) primary key,
  channel varchar(128) not null,
  topic varchar(256) not null,
  msg_key varchar(256),
  payload_json nvarchar(max),
  status varchar(32) not null,
  attempts int not null default 0,
  last_error varchar(2000),
  published_at datetime2 not null,
  claimed_at datetime2,
  consumed_at datetime2,
  app_name varchar(256)
);

if object_id('tql_queue_consumed', 'U') is null
create table tql_queue_consumed (
  channel varchar(128) not null,
  topic varchar(256) not null,
  idem_key varchar(256) not null,
  consumed_at datetime2 not null,
  primary key (channel, topic, idem_key)
);
