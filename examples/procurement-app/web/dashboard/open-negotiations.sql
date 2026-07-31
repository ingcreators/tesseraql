select o.id, p.name as partner, o.proposed_date, o.slip_days
from orders o
join partners p on p.id = o.partner_id
join tql_workflow_instance wi
  on wi.doc_type = 'order' and wi.doc_id = o.id and wi.current_state = 'date_proposed'
order by o.slip_days desc
