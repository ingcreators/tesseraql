-- Adds one role to a group's bundle. Idempotent through insert-where-not-exists, and zero
-- rows means the group or the role names nothing.
insert into tql_group_roles (group_id, role_id)
select g.group_id, r.role_id
from tql_groups g, tql_roles r
where g.group_code = /* groupCode */ 'OPS'
  and r.role_code = /* roleCode */ 'orders.approver'
  and not exists (
    select 1 from tql_group_roles gr
    where gr.group_id = g.group_id and gr.role_id = r.role_id
  )
;
