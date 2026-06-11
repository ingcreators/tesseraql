select
  u.user_id      as user_id,
  u.login_id     as login_id,
  u.display_name as display_name,
  u.email        as email,
  u.status       as status,
  u.tenant_id    as tenant_id,
  u.version      as version
from
  tql_users u
where
  u.login_id = /* loginId */ 'admin'
/*%if tenantId != null */
  and u.tenant_id = /* tenantId */ 'tenant-a'
/*%end*/
;
