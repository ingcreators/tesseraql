select
  u.user_id      as user_id,
  u.login_id     as login_id,
  u.display_name as display_name,
  u.email        as email,
  u.status       as status,
  u.tenant_id    as tenant_id
from
  tql_users u
/*%if tenantId != null */
where
  u.tenant_id = /* tenantId */ 'tenant-a'
/*%end*/
order by
  u.login_id
;
