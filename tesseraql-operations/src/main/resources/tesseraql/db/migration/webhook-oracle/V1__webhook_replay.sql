-- TesseraQL inbound-webhook replay cache (roadmap Phase 26), Oracle variant.

create table tql_webhook_seen (
  delivery_id varchar2(256) primary key,
  expires_at timestamp not null
);
