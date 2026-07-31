select c.id, c.rfq_id, q.title, c.status, c.submitted_at,
       (select count(*) from quote_lines l
        where l.quote_id = c.id and l.unit_price is null) as unpriced_lines
from quotes c
join rfqs q on q.id = c.rfq_id
where /*%scope quotes_scope on c */ (1=1)
order by c.id
