-- The membership window every other assignment carries: aged-out members do not list.
select ug.user_id as "value"
from tql_user_groups ug
where ug.group_id = /* groupId */ 'g-1'
  and (ug.starts_at is null or ug.starts_at <= /* now */ '2026-08-20 09:00:00')
  and (ug.ends_at is null or ug.ends_at > /* now */ '2026-08-20 09:00:00')
order by ug.user_id
