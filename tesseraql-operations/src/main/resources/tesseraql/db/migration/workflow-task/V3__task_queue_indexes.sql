-- Task queue indexes (docs/workflow-surface.md decision 6): the queue page lists a
-- principal's open tasks by (assignee, status) and (candidate_group, status), which were
-- full scans — the V1 "no separate index keeps the DDL portable" stance predates the
-- tolerated-duplicate-index recipe the outbox V11 established. Idempotency via the
-- bootstrap's tolerated duplicate-index errors.

create index idx_tql_wf_task_assignee on tql_workflow_task (assignee, status);
create index idx_tql_wf_task_group on tql_workflow_task (candidate_group, status);
