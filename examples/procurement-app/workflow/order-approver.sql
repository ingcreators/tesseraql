-- A review task only when the decision asked for one: the auto lane resolves zero
-- assignees, opens no task, and the issue transition is free to fire.
select d.manager_login as assignee
from departments d
where d.id = 'procurement'
  and /* decision.orderApproval.lane */ 'auto' = 'head_review'
