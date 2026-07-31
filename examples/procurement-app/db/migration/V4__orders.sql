-- Slice 4 (docs/procurement-demo.md): the order, created from a selected quote. The
-- creation SQL computes and stamps the selection facts (total, lowest-or-not, the
-- percentage above the lowest), so the order workflow's approval decision reads plain
-- document columns and the selection-reason rule stays a set-based check.

create table orders (
  id varchar(64) primary key,
  rfq_id varchar(64) not null references rfqs (id),
  quote_id varchar(140) not null references quotes (id),
  partner_id varchar(40) not null references partners (id),
  total_amount numeric(14, 2) not null,
  is_lowest boolean not null,
  -- Percent above the lowest submitted total (0 for the lowest itself).
  delta_pct numeric(6, 2) not null,
  selection_reason varchar(400),
  approval_lane varchar(20),
  ordered_by varchar(120) not null,
  created_at timestamp not null default now(),
  last_action varchar(40),
  acted_by varchar(120),
  unique (quote_id)
);

create table order_lines (
  order_id varchar(64) not null references orders (id),
  line_no integer not null,
  item_id varchar(40) not null references items (id),
  qty numeric(12, 2) not null,
  unit_price numeric(12, 2) not null,
  promised_date date,
  primary key (order_id, line_no)
);

-- A second RFQ with two SUBMITTED quotes, deliberately a near-miss: P-200 is lowest,
-- P-100 sits 0.32% above — the comparison, the selection-reason rule, and both
-- orderApproval lanes are all assertable from this one seed.
insert into rfqs (id, requisition_id, title, quote_due_date, created_by) values
  ('RFQ-2002', 'REQ-1001', 'Workstations for the new hires', date '2026-08-28', 'ota');

insert into rfq_suppliers (rfq_id, partner_id) values
  ('RFQ-2002', 'P-100'),
  ('RFQ-2002', 'P-200');

insert into quotes (id, rfq_id, partner_id, status, total_lines, priced_lines, submitted_at) values
  ('Q-RFQ-2002-P-100', 'RFQ-2002', 'P-100', 'submitted', 2, 2, now()),
  ('Q-RFQ-2002-P-200', 'RFQ-2002', 'P-200', 'submitted', 2, 2, now());

insert into quote_lines (quote_id, line_no, item_id, qty, unit_price, promised_date) values
  ('Q-RFQ-2002-P-100', 1, 'IT-001', 2, 250000.00, date '2026-09-01'),
  ('Q-RFQ-2002-P-100', 2, 'IT-002', 4, 30000.00, date '2026-09-01'),
  ('Q-RFQ-2002-P-200', 1, 'IT-001', 2, 245000.00, date '2026-09-05'),
  ('Q-RFQ-2002-P-200', 2, 'IT-002', 4, 32000.00, date '2026-09-05');
