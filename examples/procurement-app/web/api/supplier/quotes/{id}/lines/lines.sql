select l.line_no, l.item_id, i.name as item_name, i.unit, l.qty, l.unit_price,
       l.promised_date
from quote_lines l
join quotes c on c.id = l.quote_id
join items i on i.id = l.item_id
where l.quote_id = /* id */ 'Q-RFQ-2001-P-100'
  and /*%scope quotes_scope on c */ (1=1)
order by l.line_no
