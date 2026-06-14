-- TesseraQL messaging channel event log (roadmap Phase 27), Oracle variant. The bootstrap
-- tolerates ORA-00955 (name already used), so plain CREATE is idempotent on re-run.

create table tql_event (
  event_id varchar2(64) primary key,
  channel varchar2(128) not null,
  topic varchar2(256) not null,
  msg_key varchar2(256),
  payload_json clob,
  status varchar2(32) not null,
  attempts number(10) default 0 not null,
  last_error varchar2(2000),
  published_at timestamp not null,
  claimed_at timestamp,
  consumed_at timestamp,
  app_name varchar2(256)
);

create table tql_queue_consumed (
  channel varchar2(128) not null,
  topic varchar2(256) not null,
  idem_key varchar2(256) not null,
  consumed_at timestamp not null,
  primary key (channel, topic, idem_key)
);
