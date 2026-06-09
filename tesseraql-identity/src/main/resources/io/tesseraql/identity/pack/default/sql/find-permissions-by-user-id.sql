select distinct
  p.permission_id   as permission_id,
  p.permission_code as permission_code,
  p.permission_name as permission_name
from
  tql_user_roles ur
  join tql_role_permissions rp on rp.role_id = ur.role_id
  join tql_permissions p on p.permission_id = rp.permission_id
where
  ur.user_id = /* userId */ 'u1'

union

select distinct
  p.permission_id   as permission_id,
  p.permission_code as permission_code,
  p.permission_name as permission_name
from
  tql_user_groups ug
  join tql_group_roles gr on gr.group_id = ug.group_id
  join tql_role_permissions rp on rp.role_id = gr.role_id
  join tql_permissions p on p.permission_id = rp.permission_id
where
  ug.user_id = /* userId */ 'u1'
;
