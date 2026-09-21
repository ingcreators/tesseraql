-- The receipt notice's rows (docs/procurement-documents-and-edi.md decision 3): the shipment
-- export's columns, narrowed to the delivery notes received on the business date. A scheduled
-- firing binds the firing's date; a manual run names the date it is for.
select s.delivery_note_no, s.order_id, o.partner_id, p.name as partner_name,
       s.ship_date, s.carrier, s.received_at
from shipments s
join orders o on o.id = s.order_id
join partners p on p.id = o.partner_id
where cast(s.received_at as date) = cast(/* batch.businessDate */ '2026-09-21' as date)
order by s.delivery_note_no
