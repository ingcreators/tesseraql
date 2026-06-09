select
  u.id,
  u.name,
  u.status,
  u.created_at
from
  users u
where
  1 = 1
/*%if q != null && q != "" */
  and u.name like /* q */ '%佐藤%'
/*%end*/
order by
  u.id
limit /* limit */ 50
offset /* offset */ 0
;
