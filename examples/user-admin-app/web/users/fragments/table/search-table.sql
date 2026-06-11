select
  u.id,
  u.name,
  u.status
from
  users u
where
  1 = 1
/*%if q != null && q != "" */
  and u.name like /* q */ '%sato%'
/*%end*/
order by
  u.id
limit 50
;
