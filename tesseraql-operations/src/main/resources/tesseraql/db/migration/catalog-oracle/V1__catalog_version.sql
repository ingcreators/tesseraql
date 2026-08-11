-- Code-catalog source versions (docs/lookups.md, decision 14), Oracle (23+) variant.
create table if not exists tql_catalog_version (
  table_name varchar2(255) not null,
  version number(19) not null,
  updated_at timestamp not null,
  primary key (table_name)
);
