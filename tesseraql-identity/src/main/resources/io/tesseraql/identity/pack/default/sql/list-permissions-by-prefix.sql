select
  p.permission_code as permission_code,
  p.permission_name as permission_name
from
  tql_permissions p
where
  p.permission_code like concat(/* prefix */ 'app.', '%') escape '#'
order by
  p.permission_code
;
