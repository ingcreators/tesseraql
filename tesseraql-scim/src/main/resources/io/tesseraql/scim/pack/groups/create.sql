-- The bundled managed Group contract (docs/contract-sql-execution.md structural decision 6).
-- group_code AND group_name both come from displayName: the code is what assignment rules and
-- the admin surface join on, so it is the name an administrator recognises. The id is minted by
-- the service (grp-<uuid>) because tql_groups.group_id is a supplied varchar - there is nothing
-- for a database to generate.
insert into tql_groups (group_id, group_code, group_name, tenant_id, external_id)
values (
  /* id */ 'g-1',
  /* displayName */ 'Group',
  /* displayName */ 'Group',
  null,
  /* externalId */ null
)
