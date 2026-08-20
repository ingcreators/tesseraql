-- Lands an elevation as an ordinary windowed assignment (docs/access-governance.md
-- structural decision 3), stamped with its own provenance so it is distinguishable from a
-- standing grant. Nothing downstream needs to know: the resolution contracts already
-- filter the window, so the elevation stops resolving when it closes and needs no sweeper.
insert into tql_user_roles (user_id, role_id, source, starts_at, ends_at)
select
  /* userId */ 'u1',
  r.role_id,
  'elevation',
  /* startsAt */ '2026-01-01 00:00:00',
  /* endsAt */ '2026-01-01 01:00:00'
from
  tql_roles r
where
  r.role_code = /* roleCode */ 'orders.approver'
;
