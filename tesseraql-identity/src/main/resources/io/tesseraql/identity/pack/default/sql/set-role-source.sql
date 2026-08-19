update tql_roles
set source = /* source */ 'orphaned'
where
  role_code = /* roleCode */ 'x'
;
