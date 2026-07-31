insert into order_lines (order_id, line_no, item_id, qty, unit_price, promised_date)
select o.id, l.line_no, l.item_id, l.qty, l.unit_price, l.promised_date
from orders o
join quote_lines l on l.quote_id = o.quote_id
where o.quote_id = /* quoteId */ 'Q-RFQ-2001-P-100'
on conflict (order_id, line_no) do nothing
