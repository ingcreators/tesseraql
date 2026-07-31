select r.id, r.title, r.department, r.category, r.amount, r.internal_estimate,
       r.requested_by, r.approval_route, r.last_action
from purchase_requisitions r
where 1 = 1
  /*%if q */
  and r.title ilike '%' || /* q */ 'desk' || '%'
  /*%end*/
  and /*%scope requisitions_scope on r */ (1=1)
order by r.id
