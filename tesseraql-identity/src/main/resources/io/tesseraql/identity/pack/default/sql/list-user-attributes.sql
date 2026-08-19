select
  a.name  as name,
  a.value as value
from
  tql_user_attributes a
where
  a.user_id = /* userId */ 'u1'
order by
  a.name
;
