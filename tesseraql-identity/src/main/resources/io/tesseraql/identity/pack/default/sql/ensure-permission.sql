-- Creates a permission by code if it does not exist yet (idempotent bootstrap helper).
-- PostgreSQL syntax (on conflict); other dialects override with a <dialect>.sql variant.
insert into tql_permissions (permission_id, permission_code, permission_name)
values
  ( /* permissionId */ 'ops.app.*',
    /* permissionCode */ 'ops.app.*',
    /* permissionName */ 'ops.app.*' )
on conflict (permission_code) do nothing
;
