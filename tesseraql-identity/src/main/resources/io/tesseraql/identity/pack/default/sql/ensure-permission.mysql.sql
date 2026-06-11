-- Creates a permission by code if it does not exist yet (idempotent bootstrap helper).
-- MySQL variant.
insert ignore into tql_permissions (permission_id, permission_code, permission_name)
values
  ( /* permissionId */ 'ops.app.*',
    /* permissionCode */ 'ops.app.*',
    /* permissionName */ 'ops.app.*' )
;
