-- The supplier's inbox: issued RFQs this partner is invited to. The invitation row
-- carries the scope; the issued check reads the managed workflow state directly —
-- process state is just a row (docs/approval-workflow.md).
select q.id, q.title, q.quote_due_date
from rfqs q
join rfq_suppliers s on s.rfq_id = q.id
where exists (
    select 1 from tql_workflow_instance wi
    where wi.doc_type = 'rfq' and wi.doc_id = q.id and wi.current_state = 'issued')
  and /*%scope quotes_scope on s */ (1=1)
order by q.quote_due_date, q.id
