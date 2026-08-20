-- What a person may take but does not hold (docs/access-governance.md structural decision
-- 3). This read never runs at sign-in: an eligibility grants nothing, so it must stay out
-- of the principal entirely -- absent from the union, absent from the grants, invisible to
-- every policy. It is read only by the surfaces that offer elevation.
--
-- `held_until` is the elevation already standing, when there is one, so the card can offer
-- "end early" instead of "elevate".
select
  e.user_id       as user_id,
  r.role_code     as role_code,
  r.role_name     as role_name,
  r.application   as application,
  e.max_minutes   as max_minutes,
  e.requires_reason as requires_reason,
  e.requires_approval as requires_approval,
  e.expires_at    as expires_at,
  (select ur.ends_at from tql_user_roles ur
     where ur.user_id = e.user_id and ur.role_id = e.role_id
       and ur.source = 'elevation') as held_until
from
  tql_role_eligibility e
  join tql_roles r on r.role_id = e.role_id
where
  1 = 1
/*%if userId != null */
  and e.user_id = /* userId */ 'u1'
/*%end*/
  and (e.expires_at is null or e.expires_at > current_timestamp)
order by
  e.user_id, r.role_code
;
