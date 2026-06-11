select
  count(*) as count
from
  tql_users u
/*%if tenantId != null */
where
  u.tenant_id = /* tenantId */ 'tenant-a'
/*%end*/
;
