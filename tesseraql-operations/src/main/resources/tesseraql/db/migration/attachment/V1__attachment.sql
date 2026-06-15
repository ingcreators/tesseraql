-- TesseraQL attachments and object storage (roadmap Phase 30 slice 1): the managed attachment
-- metadata table. One row per uploaded object, tying a durable blob (addressed by storage_key in a
-- BlobStore) to an owning business record (entity + entity_id). scan_status is reserved for the
-- scan-hook (slice 3); slice-1 uploads record 'clean'. No separate index keeps the DDL portable
-- across PostgreSQL and MySQL.

create table if not exists tql_attachment (
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
  created_at timestamp not null,
  tenant_id varchar(64)
);
