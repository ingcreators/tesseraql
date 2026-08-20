select
  p.permission_id   as permission_id,
  p.permission_code as permission_code,
  p.permission_name as permission_name
from
  tql_user_roles ur
  join tql_role_permissions rp on rp.role_id = ur.role_id
  join tql_permissions p on p.permission_id = rp.permission_id
where
  ur.user_id = /* userId */ 'u1'
  and (ur.starts_at is null or ur.starts_at <= current_timestamp)
  and (ur.ends_at is null or ur.ends_at > current_timestamp)

union

select
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
  and (ug.starts_at is null or ug.starts_at <= current_timestamp)
  and (ug.ends_at is null or ug.ends_at > current_timestamp)

union

select
  p.permission_id   as permission_id,
  p.permission_code as permission_code,
  p.permission_name as permission_name
from
  tql_user_permissions up
  join tql_permissions p on p.permission_id = up.permission_id
where
  up.user_id = /* userId */ 'u1'
  and (up.starts_at is null or up.starts_at <= current_timestamp)
  and (up.ends_at is null or up.ends_at > current_timestamp)
;
