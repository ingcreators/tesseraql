-- TesseraQL SCIM extension table (design ch. 10.15), SQL Server variant.
if object_id('tql_scim_resource_map', 'U') is null
create table tql_scim_resource_map (
  local_id  varchar(255) primary key,
  remote_id varchar(255) not null
);
