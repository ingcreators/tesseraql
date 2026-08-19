select
  p.permission_code as permission_code
from
  tql_user_permissions up
  join tql_permissions p on p.permission_id = up.permission_id
where
  up.user_id = /* userId */ 'u1'
  and (up.starts_at is null or up.starts_at <= current_timestamp)
  and (up.ends_at is null or up.ends_at > current_timestamp)
order by
  p.permission_code
;
