select
  r.role_id   as role_id,
  r.role_code as role_code,
  r.role_name as role_name,
  'DIRECT'    as grant_type
from
  tql_user_roles ur
  join tql_roles r on r.role_id = ur.role_id
where
  ur.user_id = /* userId */ 'u1'

union

select
  r.role_id   as role_id,
  r.role_code as role_code,
  r.role_name as role_name,
  'GROUP'     as grant_type
from
  tql_user_groups ug
  join tql_group_roles gr on gr.group_id = ug.group_id
  join tql_roles r on r.role_id = gr.role_id
where
  ug.user_id = /* userId */ 'u1'
;
