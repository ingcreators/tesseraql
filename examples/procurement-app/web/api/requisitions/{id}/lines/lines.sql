-- Lines ride the header's reach: the scope sits on the joined header row, so a caller
-- who cannot see the requisition cannot see its lines either (docs/data-scoping.md
-- "The scoped column may live in any joined table").
select l.line_no, l.item_id, i.name as item_name, i.unit, l.qty, l.desired_date
from requisition_lines l
join purchase_requisitions r on r.id = l.requisition_id
join items i on i.id = l.item_id
where l.requisition_id = /* id */ 'REQ-1001'
  and /*%scope requisitions_scope on r */ (1=1)
order by l.line_no
