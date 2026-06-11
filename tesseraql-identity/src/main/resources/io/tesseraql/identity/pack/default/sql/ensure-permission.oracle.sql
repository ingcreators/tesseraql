-- Creates a permission by code if it does not exist yet. Oracle MERGE variant.
merge into tql_permissions p
using (select /* permissionId */ 'ops.app.*' permission_id,
              /* permissionCode */ 'ops.app.*' permission_code,
              /* permissionName */ 'ops.app.*' permission_name
       from dual) s
on (p.permission_code = s.permission_code)
when not matched then insert (permission_id, permission_code, permission_name)
  values (s.permission_id, s.permission_code, s.permission_name)
