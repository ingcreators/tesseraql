-- The supplier master behind the request form's reference lookup
-- (docs/reference-lookup.md): a business master is searched through its own route,
-- never held in memory like a code catalog. The request's supplier is optional —
-- a requester who knows the supplier names it, purchasing fills the rest in later.
create table suppliers (
  id varchar(40) primary key,
  supplier_code varchar(20) not null unique,
  name varchar(200) not null
);

insert into suppliers (id, supplier_code, name) values
  ('sup-100', 'S-100', 'Northwind Office Supply'),
  ('sup-200', 'S-200', 'Aurora Desks and Seating'),
  ('sup-300', 'S-300', 'Cascade AV Equipment');

alter table purchase_requests
  add column supplier_id varchar(40) references suppliers (id);
