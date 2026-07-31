-- Slice 6 (docs/procurement-demo.md): shipment and goods receipt. One order ships
-- once (the split-shipment fence); receipt closes the chain on the same row.

create table shipments (
  id integer generated always as identity primary key,
  order_id varchar(64) not null unique references orders (id),
  ship_date date not null,
  carrier varchar(120) not null,
  delivery_note_no varchar(60) not null,
  shipped_by varchar(120) not null,
  created_at timestamp not null default now(),
  received_at timestamp,
  received_by varchar(120)
);
