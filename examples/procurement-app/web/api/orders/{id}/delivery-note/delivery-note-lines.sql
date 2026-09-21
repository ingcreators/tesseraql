-- The delivery note's lines: what ships is what was ordered (one shipment per order).
select l.line_no, i.name as item_name, i.unit, l.qty
from order_lines l
join orders o on o.id = l.order_id
join items i on i.id = l.item_id
where l.order_id = /* id */ 'ORD-0'
  and /*%scope quotes_scope on o */ (1=1)
order by l.line_no
