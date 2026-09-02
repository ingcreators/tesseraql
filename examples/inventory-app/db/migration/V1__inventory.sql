-- Inventory: products with stock levels. Seeded so the five-minute path has data.
create table products (
  id bigint generated always as identity primary key,
  sku varchar(40) not null unique,
  name varchar(200) not null,
  category varchar(60) not null,
  stock integer not null default 0,
  reorder_level integer not null default 10,
  updated_at timestamp not null default now()
);

insert into products (sku, name, category, stock, reorder_level) values
  ('KB-101', 'Mechanical keyboard', 'peripherals', 42, 10),
  ('MS-230', 'Wireless mouse', 'peripherals', 7, 15),
  ('MN-274', '27-inch monitor', 'displays', 18, 5),
  ('DK-450', 'Standing desk', 'furniture', 3, 5),
  -- The two SKUs the shipped supplier price feed quotes (data/drops/supplier-prices.csv).
  -- They are here so the feed is about this catalogue: the analytics drop and the reviewed
  -- upload both read that file, and a price for a product nobody sells teaches nothing.
  ('WIDGET-1', 'Widget', 'peripherals', 60, 10),
  ('GADGET-7', 'Gadget', 'peripherals', 55, 10);
