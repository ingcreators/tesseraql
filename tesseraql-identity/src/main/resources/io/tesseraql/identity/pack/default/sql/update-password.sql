-- Rotates a user's own password credential (roadmap Phase 48, the account surface's
-- self-service change). The caller has already verified the current credential via
-- find-credential-by-login; this contract only writes the new hash. Portable UPDATE, no
-- dialect variants needed.
update tql_users
set
  password_hash   = /* passwordHash */ 'c2FsdA==:aGFzaA==',
  password_algo   = 'pbkdf2',
  password_params = /* passwordParams */ 'iterations=100000,keyLength=256'
where
  login_id = /* loginId */ 'admin'
;
