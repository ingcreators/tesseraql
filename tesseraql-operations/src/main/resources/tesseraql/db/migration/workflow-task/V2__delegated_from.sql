-- Who a task was MEANT for when an absence rule redirected it (roadmap Phase 52):
-- nullable; set at assignment time only. PostgreSQL, MySQL and H2 parse this; Oracle and
-- SQL Server take no `column` keyword on ADD and have their own variants beside this one
-- (they did not, and a workflow app could not boot on either vendor —
-- docs/audit-low-leads.md slice 2b's dialect check found it). The re-runnable ensureSchema
-- bootstrap gets its idempotency from the tolerated duplicate-column errors in SqlScripts
-- (the tql_session V2 precedent).
alter table tql_workflow_task add column delegated_from varchar(256);
