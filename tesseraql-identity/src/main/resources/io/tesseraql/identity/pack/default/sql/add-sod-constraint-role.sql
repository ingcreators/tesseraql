-- Adds one role code to a constraint's mutually exclusive set (docs/access-governance.md
-- structural decision 2). Idempotent through the insert-where-not-exists form the identity
-- pack already uses, so re-adding the same code is a no-op on every dialect. The role must
-- exist: a constraint naming a code nothing grants can never fire, and would read as
-- protection that is not there.
insert into tql_sod_constraint_roles (constraint_id, role_code)
select /* constraintId */ 'sod-1', r.role_code
from tql_roles r
where r.role_code = /* roleCode */ 'orders.approver'
  and not exists (
    select 1 from tql_sod_constraint_roles cr
    where cr.constraint_id = /* constraintId */ 'sod-1' and cr.role_code = r.role_code
  )
;
