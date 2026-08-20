-- Puts one person in a group, with the same validity window every other assignment
-- carries (docs/access-governance.md structural decision 4). Zero rows means the group or
-- the person names nothing; the caller turns that into a refusal rather than a silent
-- no-op, because a membership nobody notices failing is the kind of gap this campaign
-- exists to close.
insert into tql_user_groups (user_id, group_id, source, starts_at, ends_at)
select
  /* userId */ 'u1',
  g.group_id,
  /* source */ 'admin',
  /* startsAt */ null,
  /* endsAt */ null
from tql_groups g
where g.group_code = /* groupCode */ 'OPS'
  and exists (select 1 from tql_users u where u.user_id = /* userId */ 'u1')
  and not exists (
    select 1 from tql_user_groups ug
    where ug.user_id = /* userId */ 'u1' and ug.group_id = g.group_id
  )
;
