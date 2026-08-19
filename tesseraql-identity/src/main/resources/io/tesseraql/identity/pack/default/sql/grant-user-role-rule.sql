insert into tql_user_roles (user_id, role_id, source, starts_at, ends_at)
select /* userId */ 'u1', r.role_id, 'rule', null, null
from tql_roles r
where r.role_code = /* roleCode */ 'orders.approver'
  and not exists (
    select 1 from tql_user_roles ur
    where ur.user_id = /* userId */ 'u1' and ur.role_id = r.role_id
  )
;
