-- The probe's two tables (docs/deployment-maturity.md decision 10): one row per tick of the
-- fixed-delay job, one row per alert the application received through its own webhook route.
create table probe_ticks (
  id serial primary key,
  ticked_at timestamp not null default now()
);

create table probe_alerts (
  event_id varchar(64) primary key,
  code varchar(32) not null,
  node varchar(256),
  scope varchar(32),
  received_at timestamp not null default now()
);
