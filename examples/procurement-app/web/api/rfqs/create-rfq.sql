insert into rfqs (id, requisition_id, title, quote_due_date, created_by)
select 'RFQ-' || substr(gen_random_uuid()::text, 1, 8),
       r.id, r.title, cast(/* quoteDueDate */ '2026-08-14' as date), /* audit.user */ 'someone'
from purchase_requisitions r
where r.id = /* requisitionId */ 'REQ-1002'
