-- The HTML list deliberately omits the buyer-internal estimate: views cannot mask
-- per role yet (docs/declarative-views.md), so the page simply never selects it —
-- the masked JSON surface is web/api/requisitions.
select r.id, r.title, r.department, r.category, r.amount, r.requested_by, r.last_action
from purchase_requisitions r
where 1 = 1
  /*%if q */
  and r.title ilike '%' || /* q */ 'desk' || '%'
  /*%end*/
  and /*%scope requisitions_scope on r */ (1=1)
order by r.id
