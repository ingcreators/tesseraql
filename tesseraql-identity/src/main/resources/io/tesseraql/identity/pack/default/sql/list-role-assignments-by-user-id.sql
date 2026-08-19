select
  r.role_id     as role_id,
  r.role_code   as role_code,
  r.role_name   as role_name,
  r.application as application,
  ur.source     as source,
  ur.starts_at  as starts_at,
  ur.ends_at    as ends_at
from
  tql_user_roles ur
  join tql_roles r on r.role_id = ur.role_id
where
  ur.user_id = /* userId */ 'u1'
order by
  r.role_code
;
