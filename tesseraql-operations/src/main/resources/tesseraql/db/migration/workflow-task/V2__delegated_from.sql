-- Who a task was MEANT for when an absence rule redirected it (roadmap Phase 52):
-- nullable; set at assignment time only. Plain statement so every dialect parses it;
-- the re-runnable ensureSchema bootstrap gets its idempotency from the tolerated
-- duplicate-column errors in SqlScripts (the tql_session V2 precedent).
alter table tql_workflow_task add column delegated_from varchar(256);
