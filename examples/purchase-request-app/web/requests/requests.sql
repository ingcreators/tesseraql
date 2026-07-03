select id, title, amount, requested_by, created_at, last_action
from purchase_requests
/*%if q */
where lower(title) like lower('%' || /* q */ 'desk' || '%')
/*%end*/
order by created_at desc
