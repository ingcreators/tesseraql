-- The acting role the caller had activated when the audited route ran
-- (docs/application-roles.md): null for every request outside the activation model.
-- SQL Server's duplicate-column error is not in the tolerated set, so the guard is explicit.
if col_length('tql_route_audit', 'acting_role') is null alter table tql_route_audit add acting_role varchar(200);
