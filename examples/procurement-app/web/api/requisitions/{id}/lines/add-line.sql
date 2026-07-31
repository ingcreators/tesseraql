-- Appends the next line. The scope on the header subquery keeps a caller from writing
-- lines onto a requisition outside their reach (docs/data-scoping.md "Writes are scoped
-- the same way").
insert into requisition_lines (requisition_id, line_no, item_id, qty, desired_date)
select r.id,
       coalesce((select max(l.line_no) from requisition_lines l
                 where l.requisition_id = r.id), 0) + 1,
       /* itemId */ 'OF-001', /* qty */ 1, cast(/* desiredDate */ '2026-09-01' as date)
from purchase_requisitions r
where r.id = /* id */ 'REQ-1001'
  and /*%scope requisitions_scope on r */ (1=1)
