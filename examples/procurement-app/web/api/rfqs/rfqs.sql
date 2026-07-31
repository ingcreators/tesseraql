select q.id, q.requisition_id, q.title, q.quote_due_date, q.created_by, q.last_action,
       (select count(*) from rfq_suppliers s where s.rfq_id = q.id) as invited
from rfqs q
order by q.id
