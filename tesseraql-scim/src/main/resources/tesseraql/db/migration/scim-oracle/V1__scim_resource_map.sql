-- TesseraQL SCIM extension table (design ch. 10.15), Oracle (23+) variant.
create table tql_scim_resource_map (
  local_id  varchar2(255) primary key,
  remote_id varchar2(255) not null
);
