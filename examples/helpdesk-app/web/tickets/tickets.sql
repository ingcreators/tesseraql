select id, subject, priority, requester, assignee, status, created_at
from tickets
where 1 = 1
/*%if q */
  and lower(subject) like lower('%' || /* q */ 'vpn' || '%')
/*%end*/
/*%if status */
  and status = /* status */ 'open'
/*%end*/
/*%if priority */
  and priority = /* priority */ 'high'
/*%end*/
order by created_at desc
