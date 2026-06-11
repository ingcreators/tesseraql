-- Creates or updates a bootstrap administrator (design ch. 10.3, 18 identity goals): upserts by
-- login id so the contract can run on every deploy, rotating the password hash in place.
-- PostgreSQL syntax (on conflict); other dialects override with a <dialect>.sql variant.
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
on conflict (login_id) do update set
  status = 'ACTIVE',
  password_hash = excluded.password_hash,
  password_algo = excluded.password_algo,
  password_params = excluded.password_params
;
