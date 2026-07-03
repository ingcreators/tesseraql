select
  u.status,
  count(*) as n
from
  users u
group by
  u.status
order by
  u.status
;
