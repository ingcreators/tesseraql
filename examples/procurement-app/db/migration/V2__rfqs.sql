-- Slice 2 (docs/procurement-demo.md): the RFQ leg. Partners are the supplier master,
-- rfqs the second workflow document of the chain, rfq_suppliers the invitations.
-- Seeded with one draft RFQ over the seeded sales requisition so the five-minute path
-- can submit -> issue without any authoring.

create table partners (
  id varchar(40) primary key,
  name varchar(200) not null,
  contact_email varchar(200) not null
);

create table rfqs (
  id varchar(64) primary key,
  requisition_id varchar(64) not null references purchase_requisitions (id),
  title varchar(200) not null,
  -- The business quote window, shown to suppliers; the engine-side reminder deadline
  -- (workflow/rfq.yml deadlines:) runs on its own within: clock.
  quote_due_date date,
  created_by varchar(120) not null,
  created_at timestamp not null default now(),
  last_action varchar(40),
  acted_by varchar(120)
);

create table rfq_suppliers (
  rfq_id varchar(64) not null references rfqs (id),
  partner_id varchar(40) not null references partners (id),
  invited_at timestamp not null default now(),
  primary key (rfq_id, partner_id)
);

insert into partners (id, name, contact_email) values
  ('P-100', 'Kita Trading Co.', 'sales@kita-trading.example.com'),
  ('P-200', 'Minami Office Supply', 'quotes@minami-office.example.com');

insert into rfqs (id, requisition_id, title, quote_due_date, created_by) values
  ('RFQ-2001', 'REQ-1002', 'Task chairs for the sales floor', date '2026-08-14', 'ota');

insert into rfq_suppliers (rfq_id, partner_id) values
  ('RFQ-2001', 'P-100'),
  ('RFQ-2001', 'P-200');
