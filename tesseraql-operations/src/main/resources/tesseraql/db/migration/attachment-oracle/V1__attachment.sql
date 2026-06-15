-- TesseraQL attachments (roadmap Phase 30 slice 1), Oracle variant. The bootstrap tolerates
-- ORA-00955 (name already used), so plain CREATE is idempotent on re-run.

create table tql_attachment (
  attachment_id varchar2(64) primary key,
  entity varchar2(128) not null,
  entity_id varchar2(256) not null,
  filename varchar2(512),
  content_type varchar2(256),
  byte_size number(19) not null,
  checksum varchar2(128),
  storage_key varchar2(512) not null,
  scan_status varchar2(32) not null,
  created_by varchar2(256),
  created_at timestamp not null,
  tenant_id varchar2(64)
);
