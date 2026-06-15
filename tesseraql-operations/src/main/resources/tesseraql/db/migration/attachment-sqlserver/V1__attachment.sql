-- TesseraQL attachments (roadmap Phase 30 slice 1), SQL Server variant.

if object_id('tql_attachment', 'U') is null
create table tql_attachment (
  attachment_id varchar(64) primary key,
  entity varchar(128) not null,
  entity_id varchar(256) not null,
  filename varchar(512),
  content_type varchar(256),
  byte_size bigint not null,
  checksum varchar(128),
  storage_key varchar(512) not null,
  scan_status varchar(32) not null,
  created_by varchar(256),
  created_at datetime2 not null,
  tenant_id varchar(64)
);
