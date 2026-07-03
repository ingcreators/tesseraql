-- Purchase requests: the approval-workflow starter's single document table.
-- Seeded so the five-minute path has data on first boot (serve auto-applies migrations).
create table purchase_requests (
  id varchar(64) primary key,
  title varchar(200) not null,
  amount numeric(12, 2) not null,
  requested_by varchar(120) not null,
  created_at timestamp not null default now(),
  last_action varchar(40),
  acted_by varchar(120)
);

insert into purchase_requests (id, title, amount, requested_by) values
  ('PR-1001', 'Standing desks for the design team', 2400.00, 'aoki'),
  ('PR-1002', 'Conference tickets (2x)', 980.00, 'sato');
