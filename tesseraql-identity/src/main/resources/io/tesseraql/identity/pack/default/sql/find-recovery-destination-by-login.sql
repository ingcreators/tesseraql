-- Where a password-reset link may be sent (roadmap Phase 50): the ACTIVE user's stored
-- email. Overridable like every contract - a sql realm points this at its own users
-- table; an app whose logins are themselves addresses can select login_id as destination.
-- No row means "cannot recover this account by mail", which the caller answers neutrally.
select
  u.user_id      as user_id,
  u.email        as destination,
  u.display_name as display_name
from
  tql_users u
where
  u.login_id = /* loginId */ 'admin'
  and u.status = 'ACTIVE'
  and u.email is not null
;
