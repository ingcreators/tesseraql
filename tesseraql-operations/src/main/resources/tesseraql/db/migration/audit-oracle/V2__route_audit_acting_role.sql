-- The acting role the caller had activated when the audited route ran
-- (docs/application-roles.md): null for every request outside the activation model.
-- Idempotency comes from the tolerated ORA-01430 duplicate-column error.
alter table tql_route_audit add (acting_role varchar2(200));
