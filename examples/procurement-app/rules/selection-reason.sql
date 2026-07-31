-- A returned row is a violation: the selected quote is not the lowest submitted total
-- for its RFQ and no reason was given.
select 'reason' as field
from quotes c
where c.id = /* quoteId */ 'Q-RFQ-2001-P-100'
  and cast(/* reason */ 'because' as varchar) is null
  and (select coalesce(sum(l.qty * l.unit_price), 0)
       from quote_lines l where l.quote_id = c.id)
      > (select min(t.total)
         from (select q2.id, sum(l2.qty * l2.unit_price) as total
               from quotes q2
               join quote_lines l2 on l2.quote_id = q2.id
               where q2.rfq_id = c.rfq_id and q2.status = 'submitted'
               group by q2.id) t)
