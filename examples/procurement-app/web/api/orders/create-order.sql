-- The selection facts are computed here, not client-asserted: total from the chosen
-- quote's lines, lowest/delta against every submitted quote of the same RFQ. A quote
-- already ordered (unique (quote_id)) or not yet submitted inserts nothing.
insert into orders (id, rfq_id, quote_id, partner_id, total_amount, is_lowest, delta_pct,
                    selection_reason, ordered_by)
select 'ORD-' || substr(gen_random_uuid()::text, 1, 8),
       c.rfq_id, c.id, c.partner_id, t.total,
       t.total = m.lowest,
       round((t.total - m.lowest) * 100.0 / m.lowest, 2),
       /* reason */ 'best delivery terms',
       /* audit.user */ 'someone'
from quotes c
join (select l.quote_id, sum(l.qty * l.unit_price) as total
      from quote_lines l group by l.quote_id) t on t.quote_id = c.id
join (select q2.rfq_id, min(t2.total) as lowest
      from quotes q2
      join (select l2.quote_id, sum(l2.qty * l2.unit_price) as total
            from quote_lines l2 group by l2.quote_id) t2 on t2.quote_id = q2.id
      where q2.status = 'submitted'
      group by q2.rfq_id) m on m.rfq_id = c.rfq_id
where c.id = /* quoteId */ 'Q-RFQ-2001-P-100'
  and c.status = 'submitted'
on conflict (quote_id) do nothing
