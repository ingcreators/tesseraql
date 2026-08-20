-- Every context condition in the store, with the role it narrows, for the admin surface
-- (docs/access-governance.md structural decision 8). Ordered by role so the page groups a
-- role's conditions together, which is how they are read: within one kind any condition
-- admits, and across kinds every kind must.
select
  r.role_code      as role_code,
  r.role_name      as role_name,
  r.application    as application,
  c.condition_kind as condition_kind,
  c.value          as value
from
  tql_role_conditions c
  join tql_roles r on r.role_id = c.role_id
where
  1 = 1
/*%if roleCode != null */
  and r.role_code = /* roleCode */ 'orders.approver'
/*%end*/
order by
  r.role_code, c.condition_kind, c.value
;
