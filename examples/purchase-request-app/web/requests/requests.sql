select id, title, amount, requested_by, created_at, last_action
from purchase_requests
where 1 = 1
/*%if q */
  and lower(title) like lower('%' || /* q */ 'desk' || '%')
/*%end*/
/*%if keys != null */
  and id in /* keys */('PR-1001')
/*%end*/
order by created_at desc
