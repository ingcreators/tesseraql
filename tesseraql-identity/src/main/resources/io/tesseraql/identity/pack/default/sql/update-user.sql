update
  tql_users
set
  display_name = /* displayName */ 'Administrator',
  email        = /* email */ 'admin@example.com'
where
  user_id = /* userId */ 'u1'
;
