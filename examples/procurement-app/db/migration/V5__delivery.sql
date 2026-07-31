-- Slice 5 (docs/procurement-demo.md): delivery-date negotiation. The tolerance lives
-- in an app-owned rule table (the table-backed decision source, docs/decision-tables.md)
-- procurement maintains at runtime; a NULL cell is the wildcard, priority resolves.

create table delivery_tolerances (
  id integer primary key,
  slip_min numeric(6, 0),
  slip_max numeric(6, 0),
  action varchar(20) not null,
  priority integer not null
);

insert into delivery_tolerances (id, slip_min, slip_max, action, priority) values
  (1, 0, 5, 'auto_confirm', 1),
  (2, null, null, 'review', 9);

alter table orders add column proposed_date date;
alter table orders add column slip_days numeric(6, 0);

create table date_change_requests (
  id integer generated always as identity primary key,
  order_id varchar(64) not null references orders (id),
  proposed_date date not null,
  slip_days numeric(6, 0) not null,
  resolution varchar(20),
  requested_by varchar(120) not null,
  created_at timestamp not null default now()
);
