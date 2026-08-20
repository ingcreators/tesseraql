-- Asks for a role. Only a role that has an owner can be asked for, which the insert
-- enforces rather than trusting the page that offered it: zero rows means unrequestable.
insert into tql_access_requests (request_id, requested_at, requester_id, role_code, reason,
                                 requested_minutes, status)
select
  /* requestId */ 'rq-1',
  /* requestedAt */ '2026-08-20 09:00:00',
  /* requesterId */ 'u1',
  r.role_code,
  /* reason */ 'covering for the release',
  /* requestedMinutes */ null,
  'pending'
from tql_roles r
where r.role_code = /* roleCode */ 'orders.approver'
  and exists (select 1 from tql_role_owners o where o.role_id = r.role_id)
;
