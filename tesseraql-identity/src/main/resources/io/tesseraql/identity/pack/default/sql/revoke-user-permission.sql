delete from tql_user_permissions
where
  user_id = /* userId */ 'u1'
  and permission_id in
      (select permission_id from tql_permissions where permission_code = /* code */ 'x')
;
