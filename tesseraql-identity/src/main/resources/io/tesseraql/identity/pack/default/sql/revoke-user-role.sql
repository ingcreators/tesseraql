delete from tql_user_roles
where
  user_id = /* userId */ 'u1'
  and role_id in (select role_id from tql_roles where role_code = /* roleCode */ 'x')
  and source = 'admin'
;
