-- TesseraQL SCIM extension table: local-to-remote resource id mapping for outbound
-- provisioning (design ch. 10.15).
-- Applied idempotently by JdbcScimResourceMapping when the extension installs, so it must
-- stay re-runnable.

create table if not exists tql_scim_resource_map (
  local_id  varchar(255) primary key,
  remote_id varchar(255) not null
);
