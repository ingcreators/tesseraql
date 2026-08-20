-- Every separation-of-duties constraint with one of its role codes per row
-- (docs/access-governance.md structural decision 2). One joined read, so the checkpoints
-- load the whole constraint set in a single statement: a constraint with no roles cannot
-- be violated, and the left join keeps it visible on the admin page anyway.
select
  c.constraint_id   as constraint_id,
  c.constraint_name as constraint_name,
  c.severity        as severity,
  c.description     as description,
  r.role_code       as role_code
from
  tql_sod_constraints c
  left join tql_sod_constraint_roles r on r.constraint_id = c.constraint_id
order by
  c.constraint_name, c.constraint_id, r.role_code
;
