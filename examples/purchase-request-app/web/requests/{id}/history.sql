-- Managed-mode audit: every fired transition appends one tql_workflow_history row.
select transition, from_state, to_state, actor, acted_at
from tql_workflow_history
where doc_id = /* id */ 'PR-1001'
order by acted_at
