select
  r.role_id     as role_id,
  r.role_code   as role_code,
  r.role_name   as role_name,
  r.application as application,
  r.source      as source
from
  tql_roles r
where
  r.application = /* application */ 'orders'
order by
  r.role_code
;
