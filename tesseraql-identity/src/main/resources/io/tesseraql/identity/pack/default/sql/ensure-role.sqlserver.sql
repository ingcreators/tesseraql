-- Creates a role by code if it does not exist yet. SQL Server MERGE variant.
merge into tql_roles as r
using (select /* roleId */ 'iam.admin' as role_id,
              /* roleCode */ 'iam.admin' as role_code,
              /* roleName */ 'iam.admin' as role_name) as s
on (r.role_code = s.role_code)
when not matched then insert (role_id, role_code, role_name)
  values (s.role_id, s.role_code, s.role_name)
;
