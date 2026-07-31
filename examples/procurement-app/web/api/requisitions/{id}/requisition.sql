select r.id, r.title, r.department, r.category, r.amount, r.budget_label,
       r.internal_estimate, r.requested_by, r.approval_route, r.last_action, r.acted_by
from purchase_requisitions r
where r.id = /* id */ 'REQ-1001'
  and /*%scope requisitions_scope on r */ (1=1)
