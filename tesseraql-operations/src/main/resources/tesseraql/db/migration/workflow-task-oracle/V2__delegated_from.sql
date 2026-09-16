-- Who a task was MEANT for when an absence rule redirected it (roadmap Phase 52), Oracle
-- variant: Oracle takes no `column` keyword on ADD. The bootstrap tolerates ORA-01430
-- (column already exists) on the re-run, so the plain statement stays idempotent.
alter table tql_workflow_task add delegated_from varchar2(256);
