-- The quotation's header: the quote, the supplier issuing it, the RFQ it answers and the
-- department that asked. One row, read by the template as header.first; the dates are
-- rendered here so the template carries no formatting logic of its own.
select c.id, c.status,
       to_char(c.submitted_at, 'YYYY/MM/DD') as submitted_on,
       p.name as partner_name, p.contact_email,
       q.id as rfq_id, q.title as rfq_title,
       to_char(q.quote_due_date, 'YYYY/MM/DD') as quote_due_on,
       d.name as department_name,
       (select cast(coalesce(sum(l.qty * l.unit_price), 0) as numeric(14, 2))
        from quote_lines l where l.quote_id = c.id) as total_amount
from quotes c
join partners p on p.id = c.partner_id
join rfqs q on q.id = c.rfq_id
join purchase_requisitions r on r.id = q.requisition_id
join departments d on d.id = r.department
where c.id = /* id */ 'Q-RFQ-2002-P-200'
  and /*%scope quotes_scope on c */ (1=1)
