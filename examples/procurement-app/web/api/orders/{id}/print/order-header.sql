-- The purchase order's header: the order, the supplier it is addressed to, the RFQ and the
-- requisition behind it, and the department the goods go to. One row; header.first.
select o.id, o.total_amount, o.selection_reason,
       to_char(o.created_at, 'YYYY/MM/DD') as ordered_on,
       o.ordered_by,
       p.name as partner_name, p.contact_email,
       q.id as rfq_id, q.title as rfq_title,
       d.name as department_name
from orders o
join partners p on p.id = o.partner_id
join rfqs q on q.id = o.rfq_id
join purchase_requisitions r on r.id = q.requisition_id
join departments d on d.id = r.department
where o.id = /* id */ 'ORD-0'
  and /*%scope quotes_scope on o */ (1=1)
