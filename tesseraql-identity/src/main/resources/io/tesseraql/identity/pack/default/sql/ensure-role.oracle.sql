-- Creates a role by code if it does not exist yet. Oracle MERGE variant.
merge into tql_roles r
using (select /* roleId */ 'iam.admin' role_id,
              /* roleCode */ 'iam.admin' role_code,
              /* roleName */ 'iam.admin' role_name
       from dual) s
on (r.role_code = s.role_code)
when not matched then insert (role_id, role_code, role_name)
  values (s.role_id, s.role_code, s.role_name)
