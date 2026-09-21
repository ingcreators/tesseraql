-- The notices the buyer has sent, newest receipt first; the search matches the delivery
-- note's number or the order.
select n.delivery_note_no, n.order_id, n.partner_name, n.ship_date, n.carrier,
       n.received_at, n.imported_at
from receipt_notices n
where 1 = 1
/*%if q */
  and (n.delivery_note_no like '%' || /* q */ 'DN' || '%'
       or n.order_id like '%' || /* q */ 'ORD' || '%')
/*%end*/
order by n.received_at desc, n.delivery_note_no
