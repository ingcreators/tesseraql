select
  u.user_id      as user_id,
  u.login_id     as login_id,
  u.display_name as display_name,
  u.status       as status,
  r.role_code    as role_code,
  'DIRECT'       as grant_type
from
  tql_permissions p
  join tql_role_permissions rp on rp.permission_id = p.permission_id
  join tql_roles r on r.role_id = rp.role_id
  join tql_user_roles ur on ur.role_id = r.role_id
  join tql_users u on u.user_id = ur.user_id
where
  p.permission_code = /* code */ 'app.read'

union

select
  u.user_id      as user_id,
  u.login_id     as login_id,
  u.display_name as display_name,
  u.status       as status,
  r.role_code    as role_code,
  'GROUP'        as grant_type
from
  tql_permissions p
  join tql_role_permissions rp on rp.permission_id = p.permission_id
  join tql_roles r on r.role_id = rp.role_id
  join tql_group_roles gr on gr.role_id = r.role_id
  join tql_user_groups ug on ug.group_id = gr.group_id
  join tql_users u on u.user_id = ug.user_id
where
  p.permission_code = /* code */ 'app.read'

order by
  login_id, role_code
;
