-- Creates a permission by code if it does not exist yet (idempotent bootstrap helper).
-- PostgreSQL syntax (on conflict); other dialects override with a <dialect>.sql variant.
insert into tql_permissions (permission_id, permission_code, permission_name)
values
  ( /* permissionId */ 'tql.ops.view.*',
    /* permissionCode */ 'tql.ops.view.*',
    /* permissionName */ 'tql.ops.view.*' )
on conflict (permission_code) do nothing
;
