-- Ships only when the shipment is registered and the order is in the caller's own
-- partner scope: no shipment row, no transition.
update orders
set last_action = 'ship', acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
  and exists (select 1 from shipments s where s.order_id = orders.id)
  and /*%scope quotes_scope */ (1=1)
