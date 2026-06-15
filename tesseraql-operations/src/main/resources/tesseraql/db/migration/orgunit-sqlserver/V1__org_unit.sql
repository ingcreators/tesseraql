-- TesseraQL org-unit hierarchy (roadmap Phase 29 slice 2), SQL Server variant.

if object_id('tql_org_unit', 'U') is null
create table tql_org_unit (
  unit_id varchar(64) primary key,
  parent_id varchar(64),
  name varchar(256),
  tenant_id varchar(64)
);

if object_id('tql_org_closure', 'U') is null
create table tql_org_closure (
  ancestor_id varchar(64) not null,
  descendant_id varchar(64) not null,
  depth int not null,
  primary key (ancestor_id, descendant_id)
);
