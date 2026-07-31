-- Shipping demands the registered shipment; the refusal now names itself instead of
-- surfacing as a generic zero-row conflict.
select 1 from shipments where order_id = /* key */ 'ORD-0'
