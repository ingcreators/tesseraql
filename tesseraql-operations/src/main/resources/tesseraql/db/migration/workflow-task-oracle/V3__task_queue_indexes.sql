-- Task queue indexes (docs/workflow-surface.md decision 6), Oracle variant: same
-- statements as the common script; the bootstrap tolerates ORA-00955 on the re-run.

create index idx_tql_wf_task_assignee on tql_workflow_task (assignee, status);
create index idx_tql_wf_task_group on tql_workflow_task (candidate_group, status);
