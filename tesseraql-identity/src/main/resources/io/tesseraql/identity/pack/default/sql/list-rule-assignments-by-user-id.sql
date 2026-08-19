select
  r.role_code as role_code
from
  tql_user_roles ur
  join tql_roles r on r.role_id = ur.role_id
where
  ur.user_id = /* userId */ 'u1'
  and ur.source = 'rule'
;
