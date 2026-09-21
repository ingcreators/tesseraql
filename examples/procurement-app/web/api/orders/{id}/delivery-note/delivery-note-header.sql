-- The delivery note's header: the registered shipment, its order, the supplier issuing the
-- note and the department receiving the goods. No shipment, no row — and no document.
select s.delivery_note_no, to_char(s.ship_date, 'YYYY/MM/DD') as shipped_on, s.carrier,
       to_char(s.received_at, 'YYYY/MM/DD') as received_on,
       o.id as order_id, to_char(o.created_at, 'YYYY/MM/DD') as ordered_on,
       p.name as partner_name, p.contact_email,
       q.title as rfq_title,
       d.name as department_name
from shipments s
join orders o on o.id = s.order_id
join partners p on p.id = o.partner_id
join rfqs q on q.id = o.rfq_id
join purchase_requisitions r on r.id = q.requisition_id
join departments d on d.id = r.department
where s.order_id = /* id */ 'ORD-0'
  and /*%scope quotes_scope on o */ (1=1)
