-- Assigns a role (by code) to a user if not already assigned. Oracle MERGE variant.
merge into tql_user_roles ur
using (select /* userId */ 'admin' user_id, r.role_id
       from tql_roles r
       where r.role_code = /* roleCode */ 'iam.admin') s
on (ur.user_id = s.user_id and ur.role_id = s.role_id)
when not matched then insert (user_id, role_id) values (s.user_id, s.role_id)
