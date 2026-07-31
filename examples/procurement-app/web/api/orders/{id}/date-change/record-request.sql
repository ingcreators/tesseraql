insert into date_change_requests (order_id, proposed_date, slip_days, requested_by)
select o.id, o.proposed_date, o.slip_days, /* audit.user */ 'someone'
from orders o
where o.id = /* id */ 'ORD-0'
  and o.proposed_date is not null
  and /*%scope quotes_scope on o */ (1=1)
