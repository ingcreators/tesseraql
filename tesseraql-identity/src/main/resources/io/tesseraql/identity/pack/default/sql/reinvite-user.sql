-- Re-inviting a withdrawn account (docs/credential-lifecycle.md): back to INVITED with the
-- operator's latest name and address. Only while the row holds no credential - an account
-- that was ever signed into keeps its status, so an invite can never take one over.
update
  tql_users
set
  status       = 'INVITED',
  display_name = /* displayName */ 'New Hire',
  email        = /* email */ 'new-hire@example.com'
where
  user_id = /* userId */ 'u1'
  and password_hash is null
;
