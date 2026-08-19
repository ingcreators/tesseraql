select
  u.user_id      as user_id,
  u.login_id     as login_id,
  u.display_name as display_name,
  u.email        as email,
  u.status       as status,
  u.tenant_id    as tenant_id
from
  tql_user_identities i
  inner join tql_users u on u.user_id = i.user_id
where
  i.provider = /* provider */ 'https://idp.example.com'
  and i.external_subject = /* subject */ 'subject-1'
;
