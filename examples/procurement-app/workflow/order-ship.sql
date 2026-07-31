-- The shipment requirement lives in the guard (workflow/shipment-registered.sql) with
-- its own refusal code; this command carries only the caller's row authority.
update orders
set last_action = 'ship', acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
  and /*%scope quotes_scope */ (1=1)
