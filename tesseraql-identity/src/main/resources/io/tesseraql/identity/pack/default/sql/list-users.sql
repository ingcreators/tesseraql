select
  u.user_id      as user_id,
  u.login_id     as login_id,
  u.display_name as display_name,
  u.email        as email,
  u.status       as status,
  u.tenant_id    as tenant_id
from
  tql_users u
where
  1 = 1
/*%if tenantId != null */
  and u.tenant_id = /* tenantId */ 'tenant-a'
/*%end*/
/*%if q != null */
  and (lower(u.login_id) like '%' || lower(/* q */ 'ali') || '%'
    or lower(coalesce(u.display_name, '')) like '%' || lower(/* q */ 'ali') || '%'
    or lower(coalesce(u.email, '')) like '%' || lower(/* q */ 'ali') || '%')
/*%end*/
order by
  u.login_id
;
