-- The purchase order's lines: the quote's lines as copied at ordering, priced, with the
-- extended amount computed here so the document and the order total agree.
select l.line_no, i.name as item_name, i.unit, l.qty, l.unit_price,
       cast(l.qty * l.unit_price as numeric(14, 2)) as amount, l.promised_date
from order_lines l
join orders o on o.id = l.order_id
join items i on i.id = l.item_id
where l.order_id = /* id */ 'ORD-0'
  and /*%scope quotes_scope on o */ (1=1)
order by l.line_no
