select
  u.user_id         as user_id,
  u.login_id        as login_id,
  u.password_hash   as password_hash,
  u.password_algo   as password_algo,
  u.password_params as password_params,
  u.status          as status
from
  tql_users u
where
  u.login_id = /* loginId */ 'admin'
;
