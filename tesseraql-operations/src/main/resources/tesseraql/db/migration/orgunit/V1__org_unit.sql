-- TesseraQL organizational data scoping (roadmap Phase 29 slice 2): the shared org-unit hierarchy.
-- tql_org_unit holds the units and their parent links; tql_org_closure is the transitive closure
-- (every ancestor/descendant pair, depth 0 being the unit itself), so a subtree scope is a plain,
-- portable SELECT against the closure with no recursive CTE. The closure is maintained from the
-- parent graph by JdbcOrgUnitStore. This managed model is the substrate both data scoping (Phase 29)
-- and approval-workflow assignee resolution (Phase 28) build on.

create table if not exists tql_org_unit (
  unit_id varchar(64) primary key,
  parent_id varchar(64),
  name varchar(256),
  tenant_id varchar(64)
);

create table if not exists tql_org_closure (
  ancestor_id varchar(64) not null,
  descendant_id varchar(64) not null,
  depth integer not null,
  primary key (ancestor_id, descendant_id)
);
