-- The procurement demo's buyer-side foundation (docs/procurement-demo.md, slice 1):
-- departments and items are small masters, purchase_requisitions is the first
-- workflow document of the chain, requisition_lines its line items. Seeded so the
-- five-minute path has data on first boot (serve auto-applies migrations).

create table departments (
  id varchar(40) primary key,
  name varchar(120) not null,
  manager_login varchar(120) not null
);

create table items (
  id varchar(40) primary key,
  name varchar(200) not null,
  unit varchar(20) not null,
  category varchar(40) not null
);

create table purchase_requisitions (
  id varchar(64) primary key,
  title varchar(200) not null,
  department varchar(40) not null references departments (id),
  category varchar(40) not null,
  amount numeric(12, 2) not null,
  budget_label varchar(120),
  -- Buyer-internal: what we think it should cost. Masked from plain requesters on the
  -- API surface (config/tesseraql.yml req.cost) and never shown to suppliers later on.
  internal_estimate numeric(12, 2),
  requested_by varchar(120) not null,
  created_at timestamp not null default now(),
  -- The lane the approvalRoute decision stamped at submit; later transitions guard on it.
  approval_route varchar(20),
  last_action varchar(40),
  acted_by varchar(120)
);

create table requisition_lines (
  requisition_id varchar(64) not null references purchase_requisitions (id),
  line_no integer not null,
  item_id varchar(40) not null references items (id),
  qty numeric(12, 2) not null,
  desired_date date,
  primary key (requisition_id, line_no)
);

insert into departments (id, name, manager_login) values
  ('engineering', 'Engineering', 'mori'),
  ('sales', 'Sales', 'kishi'),
  ('procurement', 'Procurement', 'ota');

insert into items (id, name, unit, category) values
  ('IT-001', 'Developer workstation', 'unit', 'it-equipment'),
  ('IT-002', '27-inch monitor', 'unit', 'it-equipment'),
  ('OF-001', 'Standing desk', 'unit', 'office'),
  ('OF-002', 'Task chair', 'unit', 'office'),
  ('SV-001', 'On-site calibration service', 'case', 'services');

insert into purchase_requisitions
  (id, title, department, category, amount, budget_label, internal_estimate, requested_by)
values
  ('REQ-1001', 'Workstations for the new hires', 'engineering', 'it-equipment',
   840000.00, 'FY26 engineering equipment', 810000.00, 'aoki'),
  ('REQ-1002', 'Task chairs for the sales floor', 'sales', 'office',
   96000.00, 'FY26 sales facilities', 90000.00, 'sato');

insert into requisition_lines (requisition_id, line_no, item_id, qty, desired_date) values
  ('REQ-1001', 1, 'IT-001', 2, date '2026-09-01'),
  ('REQ-1001', 2, 'IT-002', 4, date '2026-09-01'),
  ('REQ-1002', 1, 'OF-002', 8, date '2026-08-20');
