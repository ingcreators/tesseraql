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
  -- Nested two-arg concat: the one spelling all four dialects accept (Oracle's concat
  -- is strictly two-arg; SQL Server has no ||; MySQL's || is logical OR by default).
  and (lower(u.login_id) like concat('%', concat(lower(/* q */ 'ali'), '%'))
    or lower(coalesce(u.display_name, '')) like concat('%', concat(lower(/* q */ 'ali'), '%'))
    or lower(coalesce(u.email, '')) like concat('%', concat(lower(/* q */ 'ali'), '%')))
/*%end*/
order by
  u.login_id
;
