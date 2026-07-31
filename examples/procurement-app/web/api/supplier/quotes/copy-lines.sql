-- The quote's lines mirror the requisition's, unpriced; re-running affects zero rows.
insert into quote_lines (quote_id, line_no, item_id, qty)
select c.id, l.line_no, l.item_id, l.qty
from quotes c
join rfqs q on q.id = c.rfq_id
join requisition_lines l on l.requisition_id = q.requisition_id
where q.id = /* rfqId */ 'RFQ-2001'
  and c.status = 'draft'
  and /*%scope quotes_scope on c */ (1=1)
on conflict (quote_id, line_no) do nothing
