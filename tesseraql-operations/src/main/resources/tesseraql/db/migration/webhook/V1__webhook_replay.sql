-- TesseraQL inbound-webhook replay cache (roadmap Phase 26): a seen delivery id is rejected on
-- replay until its timestamp tolerance lapses, on any node sharing the database.

create table if not exists tql_webhook_seen (
  delivery_id varchar(256) primary key,
  expires_at timestamp not null
);
