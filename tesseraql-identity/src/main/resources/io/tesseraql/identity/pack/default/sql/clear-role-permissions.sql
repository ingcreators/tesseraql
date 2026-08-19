delete from tql_role_permissions
where
  role_id in (select role_id from tql_roles where role_code = /* roleCode */ 'x')
;
