delete from tql_role_owners
where role_id in (select role_id from tql_roles where role_code = /* roleCode */ 'x')
  and owner_kind = /* ownerKind */ 'user'
  and owner_ref = /* ownerRef */ 'kenji'
;
