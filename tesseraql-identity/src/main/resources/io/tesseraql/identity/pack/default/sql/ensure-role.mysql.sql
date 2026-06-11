-- Creates a role by code if it does not exist yet (idempotent bootstrap helper). MySQL variant.
insert ignore into tql_roles (role_id, role_code, role_name)
values
  ( /* roleId */ 'iam.admin',
    /* roleCode */ 'iam.admin',
    /* roleName */ 'iam.admin' )
;
