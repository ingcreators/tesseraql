-- One shipment per order (unique order_id): re-registering is a conflict no-op; only a
-- confirmed order in the caller's partner scope takes one.
insert into shipments (order_id, ship_date, carrier, delivery_note_no, shipped_by)
select o.id, cast(/* shipDate */ '2026-09-05' as date), /* carrier */ 'Sagawa',
       /* deliveryNoteNo */ 'DN-0001', /* audit.user */ 'someone'
from orders o
where o.id = /* id */ 'ORD-0'
  and exists (
    select 1 from tql_workflow_instance wi
    where wi.doc_type = 'order' and wi.doc_id = o.id and wi.current_state = 'confirmed')
  and /*%scope quotes_scope on o */ (1=1)
on conflict (order_id) do nothing
