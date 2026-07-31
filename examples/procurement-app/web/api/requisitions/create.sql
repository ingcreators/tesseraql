insert into purchase_requisitions
  (id, title, department, category, amount, budget_label, internal_estimate, requested_by)
values ('REQ-' || substr(gen_random_uuid()::text, 1, 8),
        /* title */ 'Example', /* department */ 'engineering', /* category */ 'office',
        /* amount */ 1, /* budgetLabel */ 'FY26', /* internalEstimate */ 1,
        /* audit.user */ 'someone')
