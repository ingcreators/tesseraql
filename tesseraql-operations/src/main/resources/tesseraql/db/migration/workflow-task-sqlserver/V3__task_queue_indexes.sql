-- Task queue indexes (docs/workflow-surface.md decision 6), SQL Server variant: guarded
-- so the script stays re-runnable, as the session V4 index does.

if not exists (select 1 from sys.indexes where name = 'idx_tql_wf_task_assignee') create index idx_tql_wf_task_assignee on tql_workflow_task (assignee, status);
if not exists (select 1 from sys.indexes where name = 'idx_tql_wf_task_group') create index idx_tql_wf_task_group on tql_workflow_task (candidate_group, status);
