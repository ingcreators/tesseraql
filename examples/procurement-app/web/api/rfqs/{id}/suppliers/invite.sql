-- Inviting is idempotent per (rfq, partner): re-inviting an already-invited supplier
-- affects zero rows rather than erroring, so the client can retry safely.
insert into rfq_suppliers (rfq_id, partner_id)
select q.id, p.id
from rfqs q
join partners p on p.id = /* partnerId */ 'P-100'
where q.id = /* id */ 'RFQ-2001'
on conflict (rfq_id, partner_id) do nothing
