-- Who a task was MEANT for when an absence rule redirected it (roadmap Phase 52), SQL Server
-- variant: no `column` keyword on ADD, and the col_length guard keeps the script re-runnable,
-- as the operations V3 column add does.
if col_length('tql_workflow_task', 'delegated_from') is null
alter table tql_workflow_task add delegated_from varchar(256);
