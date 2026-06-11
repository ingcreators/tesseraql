-- Assigns a role (by code) to a user if not already assigned (idempotent bootstrap helper).
-- MySQL variant.
insert ignore into tql_user_roles (user_id, role_id)
select /* userId */ 'admin', role_id
from tql_roles
where role_code = /* roleCode */ 'iam.admin'
;
