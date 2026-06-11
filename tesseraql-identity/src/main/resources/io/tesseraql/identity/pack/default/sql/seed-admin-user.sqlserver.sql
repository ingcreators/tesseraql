-- Creates or updates a bootstrap administrator (design ch. 10.3, 18). SQL Server MERGE variant.
merge into tql_users as u
using (select /* userId */ 'admin' as user_id,
              /* loginId */ 'admin' as login_id,
              /* displayName */ 'Administrator' as display_name,
              /* passwordHash */ 'c2FsdA==:aGFzaA==' as password_hash,
              /* passwordParams */ 'iterations=100000,keyLength=256' as password_params) as s
on (u.login_id = s.login_id)
when matched then update set
  u.status = 'ACTIVE',
  u.password_hash = s.password_hash,
  u.password_algo = 'pbkdf2',
  u.password_params = s.password_params
when not matched then insert
  (user_id, login_id, display_name, status, password_hash, password_algo, password_params)
  values (s.user_id, s.login_id, s.display_name, 'ACTIVE', s.password_hash, 'pbkdf2',
          s.password_params)
;
