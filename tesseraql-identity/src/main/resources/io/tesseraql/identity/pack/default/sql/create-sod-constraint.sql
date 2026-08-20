-- Creates one separation-of-duties constraint (docs/access-governance.md structural
-- decision 2). Its role codes arrive one at a time through add-sod-constraint-role.
insert into tql_sod_constraints (constraint_id, constraint_name, severity, description)
values (
  /* constraintId */ 'sod-1',
  /* constraintName */ 'Buyer and approver',
  /* severity */ 'block',
  /* description */ null
)
;
