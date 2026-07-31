select s.partner_id, p.name, p.contact_email, s.invited_at
from rfq_suppliers s
join partners p on p.id = s.partner_id
where s.rfq_id = /* id */ 'RFQ-2001'
order by s.partner_id
