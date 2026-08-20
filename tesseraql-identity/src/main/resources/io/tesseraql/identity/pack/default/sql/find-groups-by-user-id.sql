select
  g.group_id   as group_id,
  g.group_code as group_code,
  g.group_name as group_name,
  g.tenant_id  as tenant_id
from
  tql_user_groups ug
  join tql_groups g on g.group_id = ug.group_id
where
  ug.user_id = /* userId */ 'u1'
  and (ug.starts_at is null or ug.starts_at <= current_timestamp)
  and (ug.ends_at is null or ug.ends_at > current_timestamp)
;
