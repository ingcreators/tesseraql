select
  r.role_id     as role_id,
  r.role_code   as role_code,
  r.role_name   as role_name,
  r.application as application
from
  tql_roles r
order by
  r.role_code
;
