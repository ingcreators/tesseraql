-- Every group with how many people are in it right now (docs/access-governance.md
-- structural decision 4). The count honours the membership window, so a group whose
-- members have all aged out reads as empty rather than as full.
select
  g.group_id   as group_id,
  g.group_code as group_code,
  g.group_name as group_name,
  g.tenant_id  as tenant_id,
  (select count(*) from tql_user_groups ug
     where ug.group_id = g.group_id
       and (ug.starts_at is null or ug.starts_at <= current_timestamp)
       and (ug.ends_at is null or ug.ends_at > current_timestamp)) as member_count
from
  tql_groups g
order by
  g.group_code
;
