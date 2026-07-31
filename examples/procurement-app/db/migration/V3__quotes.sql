-- Slice 3 (docs/procurement-demo.md): the supplier portal's quotes. A quote is one
-- invited supplier's answer to an issued RFQ — deliberately an app-mode status-column
-- document (the helpdesk pattern), so the suite carries both workflow modes side by
-- side. total_lines/priced_lines are counters the pricing surface maintains, letting
-- the submit guard stay a plain document-column expression.

create table quotes (
  -- Deterministic id ('Q-' || rfq || '-' || partner): starting a quote is idempotent,
  -- no cross-step key passing needed.
  id varchar(140) primary key,
  rfq_id varchar(64) not null references rfqs (id),
  partner_id varchar(40) not null references partners (id),
  status varchar(20) not null default 'draft',
  total_lines integer not null,
  priced_lines integer not null default 0,
  submitted_at timestamp,
  unique (rfq_id, partner_id)
);

create table quote_lines (
  quote_id varchar(140) not null references quotes (id),
  line_no integer not null,
  item_id varchar(40) not null references items (id),
  qty numeric(12, 2) not null,
  unit_price numeric(12, 2),
  promised_date date,
  primary key (quote_id, line_no)
);

-- One draft quote in flight for the seeded RFQ, its line still unpriced, so the suite
-- can assert the scope postures and the unpriced-submit guard without any authoring.
insert into quotes (id, rfq_id, partner_id, status, total_lines) values
  ('Q-RFQ-2001-P-100', 'RFQ-2001', 'P-100', 'draft', 1);

insert into quote_lines (quote_id, line_no, item_id, qty) values
  ('Q-RFQ-2001-P-100', 1, 'OF-002', 8);
