select c.id, c.rfq_id, q.title, c.status, c.total_lines, c.priced_lines, c.submitted_at
from quotes c
join rfqs q on q.id = c.rfq_id
where /*%scope quotes_scope on c */ (1=1)
order by c.id
