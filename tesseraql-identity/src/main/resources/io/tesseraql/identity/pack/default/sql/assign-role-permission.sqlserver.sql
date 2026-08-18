-- Grants a permission (by code) to a role (by code) if not already granted. SQL Server MERGE
-- variant.
merge into tql_role_permissions as rp
using (select r.role_id, p.permission_id
       from tql_roles r
         cross join tql_permissions p
       where r.role_code = /* roleCode */ 'iam.admin'
         and p.permission_code = /* permissionCode */ 'tql.ops.view.*') as s
on (rp.role_id = s.role_id and rp.permission_id = s.permission_id)
when not matched then insert (role_id, permission_id) values (s.role_id, s.permission_id)
;
