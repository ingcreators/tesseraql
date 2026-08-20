-- Puts a context condition on a role (docs/access-governance.md structural decision 8).
-- Idempotent through insert-where-not-exists, and zero rows means the role names nothing --
-- a condition on a role that does not exist would narrow nothing while looking like it did.
insert into tql_role_conditions (role_id, condition_kind, value)
select r.role_id, /* conditionKind */ 'network', /* value */ '10.0.0.0/8'
from tql_roles r
where r.role_code = /* roleCode */ 'orders.approver'
  and not exists (
    select 1 from tql_role_conditions c
    where c.role_id = r.role_id
      and c.condition_kind = /* conditionKind */ 'network'
      and c.value = /* value */ '10.0.0.0/8'
  )
;
