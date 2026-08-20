-- Who approves requests for a role. An owner is a person or a group; both are stored as a
-- reference the principal can be compared against without another read.
select
  r.role_code  as role_code,
  o.owner_kind as owner_kind,
  o.owner_ref  as owner_ref
from
  tql_role_owners o
  join tql_roles r on r.role_id = o.role_id
where
  1 = 1
/*%if roleCode != null */
  and r.role_code = /* roleCode */ 'orders.approver'
/*%end*/
order by
  r.role_code, o.owner_kind, o.owner_ref
;
