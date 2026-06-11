-- Grants a permission (by code) to a role (by code) if not already granted (idempotent
-- bootstrap helper). PostgreSQL syntax (on conflict); other dialects override with a
-- <dialect>.sql variant.
insert into tql_role_permissions (role_id, permission_id)
select r.role_id, p.permission_id
from tql_roles r
  cross join tql_permissions p
where r.role_code = /* roleCode */ 'iam.admin'
  and p.permission_code = /* permissionCode */ 'ops.app.*'
on conflict do nothing
;
