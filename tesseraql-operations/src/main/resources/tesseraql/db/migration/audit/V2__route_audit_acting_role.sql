-- The acting role the caller had activated when the audited route ran
-- (docs/application-roles.md): null for every request outside the activation model.
-- Idempotency comes from the tolerated duplicate-column errors (SqlScripts.applyForVendor).
alter table tql_route_audit add column acting_role varchar(200);
