-- TesseraQL inbound-webhook replay cache (roadmap Phase 26), SQL Server variant.

if object_id('tql_webhook_seen', 'U') is null
create table tql_webhook_seen (
  delivery_id varchar(256) primary key,
  expires_at datetime2 not null
);
