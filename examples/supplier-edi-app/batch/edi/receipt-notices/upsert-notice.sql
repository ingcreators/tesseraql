-- One receipt notice, keyed by the delivery note's number: a re-sent file updates the row it
-- already wrote, so a rerun on the buyer's side never duplicates one here.
insert into receipt_notices
  (delivery_note_no, order_id, partner_id, partner_name, ship_date, carrier, received_at)
values
  (/* delivery_note_no */ 'DN-0001', /* order_id */ 'ORD-0', /* partner_id */ 'P-200',
   /* partner_name */ 'Minami', cast(/* ship_date */ '2026-09-25' as date),
   /* carrier */ 'Sagawa', cast(/* received_at */ '2026-09-21 10:30:00' as timestamp))
on conflict (delivery_note_no) do update
  set order_id = excluded.order_id,
      partner_id = excluded.partner_id,
      partner_name = excluded.partner_name,
      ship_date = excluded.ship_date,
      carrier = excluded.carrier,
      received_at = excluded.received_at,
      imported_at = now()
