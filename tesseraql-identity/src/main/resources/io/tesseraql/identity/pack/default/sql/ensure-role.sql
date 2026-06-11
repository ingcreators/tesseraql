-- Creates a role by code if it does not exist yet (idempotent bootstrap helper).
-- PostgreSQL syntax (on conflict); other dialects override with a <dialect>.sql variant.
insert into tql_roles (role_id, role_code, role_name)
values
  ( /* roleId */ 'iam.admin',
    /* roleCode */ 'iam.admin',
    /* roleName */ 'iam.admin' )
on conflict (role_code) do nothing
;
