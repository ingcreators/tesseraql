select q.id, q.requisition_id, q.title, q.quote_due_date, q.created_by, q.created_at,
       q.last_action, q.acted_by
from rfqs q
where q.id = /* id */ 'RFQ-2001'
