-- Code-catalog source versions (docs/lookups.md, decision 14), SQL Server variant.
-- nvarchar(255) is 510 bytes, well under the 900-byte clustered index limit, and holds a
-- verbatim table name in any script.
if object_id('tql_catalog_version', 'U') is null
create table tql_catalog_version (
  table_name nvarchar(255) not null,
  version bigint not null,
  updated_at datetime2 not null,
  primary key (table_name)
);
