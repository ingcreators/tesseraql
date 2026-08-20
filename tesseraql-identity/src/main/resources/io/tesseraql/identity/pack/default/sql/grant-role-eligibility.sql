-- Records that a person may take a role when they need it (docs/access-governance.md
-- structural decision 3). Idempotent through insert-where-not-exists, portable on every
-- dialect; re-granting with different limits is a revoke followed by a grant, so the
-- caller sees one shape rather than an upsert that behaves differently per vendor.
insert into tql_role_eligibility (user_id, role_id, max_minutes, requires_reason,
                                  requires_approval, expires_at)
select
  /* userId */ 'u1',
  r.role_id,
  /* maxMinutes */ 60,
  /* requiresReason */ 1,
  /* requiresApproval */ 0,
  /* expiresAt */ null
from tql_roles r
where r.role_code = /* roleCode */ 'orders.approver'
  and not exists (
    select 1 from tql_role_eligibility e
    where e.user_id = /* userId */ 'u1' and e.role_id = r.role_id
  )
;
