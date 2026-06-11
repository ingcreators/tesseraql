-- Validation rule (roadmap Phase 19): the user being provisioned must exist.
-- A returned row is a violation; an empty result means the input is valid.
select
  'userName' as field
from
  (select count(*) as user_count from users where name = /* userName */ 'sato') existing
where
  existing.user_count = 0
;
