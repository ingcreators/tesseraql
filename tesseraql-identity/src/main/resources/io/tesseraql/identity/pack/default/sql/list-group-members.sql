-- One group's members with their windows and provenance, for the admin editor. Unlike the
-- resolution reads this is unfiltered: an administrator needs to see a membership that has
-- not started yet, or has ended, to edit it.
select
  ug.user_id   as user_id,
  u.login_id   as login_id,
  u.display_name as display_name,
  ug.source    as source,
  ug.starts_at as starts_at,
  ug.ends_at   as ends_at
from
  tql_user_groups ug
  left join tql_users u on u.user_id = ug.user_id
where
  ug.group_id in (select group_id from tql_groups
                  where group_code = /* groupCode */ 'OPS')
order by
  u.login_id, ug.user_id
;
