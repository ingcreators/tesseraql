select
  r.role_id     as role_id,
  r.role_code   as role_code,
  r.role_name   as role_name,
  r.application as application,
  'DIRECT'      as grant_type
from
  tql_user_roles ur
  join tql_roles r on r.role_id = ur.role_id
where
  ur.user_id = /* userId */ 'u1'
  and (ur.starts_at is null or ur.starts_at <= current_timestamp)
  and (ur.ends_at is null or ur.ends_at > current_timestamp)

union

select
  r.role_id     as role_id,
  r.role_code   as role_code,
  r.role_name   as role_name,
  r.application as application,
  'GROUP'       as grant_type
from
  tql_user_groups ug
  join tql_group_roles gr on gr.group_id = ug.group_id
  join tql_roles r on r.role_id = gr.role_id
where
  ug.user_id = /* userId */ 'u1'
;
