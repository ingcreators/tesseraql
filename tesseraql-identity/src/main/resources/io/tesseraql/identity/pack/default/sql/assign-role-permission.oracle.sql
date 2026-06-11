-- Grants a permission (by code) to a role (by code) if not already granted. Oracle MERGE variant.
merge into tql_role_permissions rp
using (select r.role_id, p.permission_id
       from tql_roles r
         cross join tql_permissions p
       where r.role_code = /* roleCode */ 'iam.admin'
         and p.permission_code = /* permissionCode */ 'ops.app.*') s
on (rp.role_id = s.role_id and rp.permission_id = s.permission_id)
when not matched then insert (role_id, permission_id) values (s.role_id, s.permission_id)
