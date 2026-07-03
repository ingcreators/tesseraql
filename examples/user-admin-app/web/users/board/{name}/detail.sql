select
  u.name,
  u.status,
  u.created_at
from
  users u
where
  u.name = /* name */ 'sato'
;
