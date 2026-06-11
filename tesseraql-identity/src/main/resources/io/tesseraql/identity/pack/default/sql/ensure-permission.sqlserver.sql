-- Creates a permission by code if it does not exist yet. SQL Server MERGE variant.
merge into tql_permissions as p
using (select /* permissionId */ 'ops.app.*' as permission_id,
              /* permissionCode */ 'ops.app.*' as permission_code,
              /* permissionName */ 'ops.app.*' as permission_name) as s
on (p.permission_code = s.permission_code)
when not matched then insert (permission_id, permission_code, permission_name)
  values (s.permission_id, s.permission_code, s.permission_name)
;
