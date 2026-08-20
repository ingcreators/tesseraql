-- The grant trail, newest first (docs/access-governance.md structural decision 1), for the
-- history page and the per-user history card. Every filter is optional: no parameter means
-- the whole store. The role join is a display convenience -- the recorded application is
-- what the row asserted when it was written, and the joined one is what the role carries
-- now, so a role that moved applications shows both truths rather than one rewritten one.
select
  h.event_id        as event_id,
  h.occurred_at     as occurred_at,
  h.actor           as actor,
  h.subject_user_id as subject_user_id,
  u.login_id        as subject_login_id,
  h.change_kind     as change_kind,
  h.subject_code    as subject_code,
  h.application     as application,
  r.application     as role_application,
  h.source          as source,
  h.starts_at       as starts_at,
  h.ends_at         as ends_at,
  h.reason          as reason,
  h.correlation     as correlation
from
  tql_grant_history h
  left join tql_users u on u.user_id = h.subject_user_id
  left join tql_roles r on r.role_code = h.subject_code
where
  1 = 1
/*%if userId != null */
  and h.subject_user_id = /* userId */ 'u1'
/*%end*/
/*%if application != null */
  and h.application = /* application */ 'orders'
/*%end*/
/*%if since != null */
  and h.occurred_at >= /* since */ '2026-01-01 00:00:00'
/*%end*/
order by
  h.occurred_at desc, h.event_id desc
;
