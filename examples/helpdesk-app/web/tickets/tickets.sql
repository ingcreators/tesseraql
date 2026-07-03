select id, subject, priority, requester, assignee, status, created_at
from tickets
/*%if q */
where lower(subject) like lower('%' || /* q */ 'vpn' || '%')
/*%end*/
order by created_at desc
