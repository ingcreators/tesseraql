insert into tql_user_roles (user_id, role_id, source, starts_at, ends_at)
select
  /* userId */ 'u1',
  r.role_id,
  'admin',
  /* startsAt */ '2026-01-01 00:00:00',
  /* endsAt */ '2027-01-01 00:00:00'
from
  tql_roles r
where
  r.role_code = /* roleCode */ 'orders.approver'
;
