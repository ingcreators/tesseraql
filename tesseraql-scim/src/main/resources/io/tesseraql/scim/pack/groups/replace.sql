-- A rename renames the code too (structural decision 6, recorded): the code follows the name
-- an administrator types, so a provisioning client's rename has the same consequence a rename
-- has anywhere in the store.
update tql_groups
set group_name  = /* displayName */ 'Group',
    group_code  = /* displayName */ 'Group',
    external_id = /* externalId */ null
where group_id = /* id */ 'g-1'
