-- Insert-able only when the caller's partner is invited (the scope on the invitation
-- row) and the RFQ is issued (the managed state, read as a row).
insert into quotes (id, rfq_id, partner_id, status)
select 'Q-' || q.id || '-' || s.partner_id, q.id, s.partner_id, 'draft'
from rfqs q
join rfq_suppliers s on s.rfq_id = q.id
where q.id = /* rfqId */ 'RFQ-2001'
  and exists (
    select 1 from tql_workflow_instance wi
    where wi.doc_type = 'rfq' and wi.doc_id = q.id and wi.current_state = 'issued')
  and /*%scope quotes_scope on s */ (1=1)
on conflict (rfq_id, partner_id) do nothing
