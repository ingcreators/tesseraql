-- TesseraQL org-unit hierarchy (roadmap Phase 29 slice 2), Oracle variant. The bootstrap tolerates
-- ORA-00955 (name already used), so plain CREATE is idempotent on re-run.

create table tql_org_unit (
  unit_id varchar2(64) primary key,
  parent_id varchar2(64),
  name varchar2(256),
  tenant_id varchar2(64)
);

create table tql_org_closure (
  ancestor_id varchar2(64) not null,
  descendant_id varchar2(64) not null,
  depth number(10) not null,
  primary key (ancestor_id, descendant_id)
);
