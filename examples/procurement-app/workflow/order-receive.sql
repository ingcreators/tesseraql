-- Receipt stamps the shipment row in the same transaction as the state advance.
update shipments
set received_at = now(), received_by = /* audit.user */ 'someone'
where order_id = /* key */ 'ORD-0'
  and received_at is null
