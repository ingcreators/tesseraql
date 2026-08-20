-- Records who approves requests for a role. Idempotent, and zero rows means the role names
-- nothing -- an owner for a role that does not exist would make it look requestable.
insert into tql_role_owners (role_id, owner_kind, owner_ref)
select r.role_id, /* ownerKind */ 'user', /* ownerRef */ 'kenji'
from tql_roles r
where r.role_code = /* roleCode */ 'orders.approver'
  and not exists (
    select 1 from tql_role_owners o
    where o.role_id = r.role_id and o.owner_kind = /* ownerKind */ 'user'
      and o.owner_ref = /* ownerRef */ 'kenji'
  )
;
