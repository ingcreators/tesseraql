-- The comparison: one row per submitted quote with its total and its distance from
-- the lowest. A variable-column supplier×line pivot is deliberately not attempted in
-- SQL — dynamic columns are outside plain-SQL reach; the tour compares totals here and
-- drills into a quote's lines on the supplier surface (docs/procurement-demo.md
-- "composition probes").
select c.id as quote_id, c.partner_id, p.name as partner_name,
       t.total,
       (t.total = min(t.total) over ()) as is_lowest,
       round((t.total - min(t.total) over ()) * 100.0 / min(t.total) over (), 2) as delta_pct
from quotes c
join partners p on p.id = c.partner_id
join (select l.quote_id, sum(l.qty * l.unit_price) as total
      from quote_lines l group by l.quote_id) t on t.quote_id = c.id
where c.rfq_id = /* id */ 'RFQ-2001'
  and c.status = 'submitted'
order by t.total, c.partner_id
