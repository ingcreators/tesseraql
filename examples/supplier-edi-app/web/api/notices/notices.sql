-- The notices the buyer has sent, newest receipt first.
select n.delivery_note_no, n.order_id, n.partner_id, n.partner_name, n.ship_date, n.carrier,
       n.received_at, n.imported_at
from receipt_notices n
order by n.received_at desc, n.delivery_note_no
