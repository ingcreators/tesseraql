-- Creates or updates a bootstrap administrator (design ch. 10.3, 18): upserts by login id so the
-- contract can run on every deploy, rotating the password hash in place. MySQL variant.
insert into tql_users
  (user_id, login_id, display_name, status, password_hash, password_algo, password_params)
values
  ( /* userId */ 'admin',
    /* loginId */ 'admin',
    /* displayName */ 'Administrator',
    'ACTIVE',
    /* passwordHash */ 'c2FsdA==:aGFzaA==',
    'pbkdf2',
    /* passwordParams */ 'iterations=100000,keyLength=256' )
as fresh
on duplicate key update
  status = 'ACTIVE',
  password_hash = fresh.password_hash,
  password_algo = fresh.password_algo,
  password_params = fresh.password_params
;
