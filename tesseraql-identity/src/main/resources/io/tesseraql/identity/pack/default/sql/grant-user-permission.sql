insert into tql_user_permissions (user_id, permission_id, starts_at, ends_at)
select
  /* userId */ 'u1',
  p.permission_id,
  /* startsAt */ '2026-01-01 00:00:00',
  /* endsAt */ '2027-01-01 00:00:00'
from
  tql_permissions p
where
  p.permission_code = /* code */ 'orders.export'
;
