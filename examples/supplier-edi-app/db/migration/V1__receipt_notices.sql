-- The supplier's side of the receipt-notice exchange (docs/procurement-documents-and-edi.md
-- decision 2): one row per delivery note the buyer reports received, keyed by the note's
-- number so a re-sent file updates rather than duplicates. Seeded with one notice so the list
-- has a row on first boot; the feed adds the rest.

create table receipt_notices (
  delivery_note_no varchar(60) primary key,
  order_id varchar(64) not null,
  partner_id varchar(40) not null,
  partner_name varchar(200) not null,
  ship_date date not null,
  carrier varchar(120) not null,
  received_at timestamp not null,
  imported_at timestamp not null default now()
);

insert into receipt_notices
  (delivery_note_no, order_id, partner_id, partner_name, ship_date, carrier, received_at)
values
  ('DN-2026-000', 'ORD-SEED', 'P-200', 'ミナミオフィスサプライ株式会社', date '2026-09-10',
   'ヤマト運輸', timestamp '2026-09-12 09:15:00');
