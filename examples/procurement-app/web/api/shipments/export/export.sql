select s.delivery_note_no, s.order_id, o.partner_id, p.name as partner_name,
       s.ship_date, s.carrier, s.received_at
from shipments s
join orders o on o.id = s.order_id
join partners p on p.id = o.partner_id
order by s.ship_date, s.delivery_note_no
