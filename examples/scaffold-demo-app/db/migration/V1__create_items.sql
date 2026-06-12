-- Starter table (tesseraql new): the Phase 18 write conventions — identity key,
-- optimistic-locking version column, audit columns, and a named unique index the
-- scaffolder maps to a field-level constraint error.
create table items (
  id bigint generated always as identity primary key,
  name varchar(200) not null,
  quantity integer not null,
  unit_price numeric(12, 2),
  due_date date,
  active boolean not null,
  note varchar(1000),
  version bigint not null,
  created_by varchar(200) not null,
  created_at timestamp not null,
  updated_by varchar(200) not null,
  updated_at timestamp not null
);

create unique index uq_items_name on items (name);

insert into items (name, quantity, unit_price, due_date, active, note, version,
                   created_by, created_at, updated_by, updated_at)
values ('First item', 1, 9.99, date '2026-01-01', true, 'Seeded by tesseraql new', 1,
        'system', current_timestamp, 'system', current_timestamp);
