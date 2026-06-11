-- Assigns a role (by code) to a user if not already assigned (idempotent bootstrap helper).
-- PostgreSQL syntax (on conflict); other dialects override with a <dialect>.sql variant.
insert into tql_user_roles (user_id, role_id)
select /* userId */ 'admin', role_id
from tql_roles
where role_code = /* roleCode */ 'iam.admin'
on conflict do nothing
;
