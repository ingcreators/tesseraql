-- One version row per source table a code catalog reads (docs/lookups.md, decision 14).
-- A maintenance write bumps the row; every runtime reads the table on an interval and reloads
-- the catalogs whose sources moved. The unit is the table, not the catalog, because twenty
-- kinds sharing one master cannot say which of them a write affected. Applied idempotently by
-- JdbcCatalogStore.ensureSchema, on the main connector — the framework's own tables live there
-- even when a catalog loads from somewhere else.
create table if not exists tql_catalog_version (
  table_name varchar(255) not null,
  version bigint not null,
  updated_at timestamp not null,
  primary key (table_name)
);
