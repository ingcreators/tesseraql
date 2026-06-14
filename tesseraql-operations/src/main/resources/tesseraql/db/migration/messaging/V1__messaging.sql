-- TesseraQL messaging channel event log (roadmap Phase 27): the durable bus behind the built-in
-- pg-notify transport. A published message is a row here; a queue-consume consumer claims it with
-- FOR UPDATE SKIP LOCKED, runs its SQL pipeline, and marks it consumed. PostgreSQL LISTEN/NOTIFY
-- only wakes the consumer sooner — durability lives in this table, so delivery is at-least-once.

create table if not exists tql_event (
  event_id varchar(64) primary key,
  channel varchar(128) not null,
  topic varchar(256) not null,
  msg_key varchar(256),
  payload_json text,
  status varchar(32) not null,
  attempts integer not null default 0,
  last_error varchar(2000),
  published_at timestamp not null,
  claimed_at timestamp,
  consumed_at timestamp,
  app_name varchar(256)
);

-- Idempotency-key deduplication: a business key consumed at most once per channel/topic, so a
-- redelivery of an already-processed key is a no-op (effectively exactly-once per key).
create table if not exists tql_queue_consumed (
  channel varchar(128) not null,
  topic varchar(256) not null,
  idem_key varchar(256) not null,
  consumed_at timestamp not null,
  primary key (channel, topic, idem_key)
);
