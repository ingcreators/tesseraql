-- Requests, newest first. Optionally narrowed to one requester (their own list) or to the
-- pending ones (an approver's queue). Owner rows travel with each request so the caller can
-- decide who may act on it without a second read per row.
select
  q.request_id        as request_id,
  q.requested_at      as requested_at,
  q.requester_id      as requester_id,
  u.login_id          as requester_login,
  u.display_name      as requester_name,
  q.role_code         as role_code,
  q.reason            as reason,
  q.requested_minutes as requested_minutes,
  q.status            as status,
  q.decided_by        as decided_by,
  q.decided_at        as decided_at,
  q.decision_note     as decision_note,
  q.granted_until     as granted_until
from
  tql_access_requests q
  left join tql_users u on u.user_id = q.requester_id
where
  1 = 1
/*%if requesterId != null */
  and q.requester_id = /* requesterId */ 'u1'
/*%end*/
/*%if status != null */
  and q.status = /* status */ 'pending'
/*%end*/
order by
  q.requested_at desc, q.request_id
;
