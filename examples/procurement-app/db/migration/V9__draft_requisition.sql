-- A just-created draft with no lines yet: the suite's fixture for the header+lines
-- detail insert (web/api/requisitions/create-lines.sql), and a clean slate for tours.
insert into purchase_requisitions
  (id, title, department, category, amount, budget_label, internal_estimate, requested_by)
values
  ('REQ-1003', 'Calibration service for the lab', 'engineering', 'services',
   180000.00, 'FY26 engineering services', null, 'aoki');
