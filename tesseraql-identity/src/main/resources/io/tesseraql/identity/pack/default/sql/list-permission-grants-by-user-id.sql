select
  p.permission_id   as permission_id,
  p.permission_code as permission_code,
  p.permission_name as permission_name,
  up.starts_at      as starts_at,
  up.ends_at        as ends_at
from
  tql_user_permissions up
  join tql_permissions p on p.permission_id = up.permission_id
where
  up.user_id = /* userId */ 'u1'
order by
  p.permission_code
;
