select
  rr.rule_id    as rule_id,
  r.role_code   as role_code,
  r.application as application,
  rr.enabled    as enabled
from
  tql_role_rules rr
  join tql_roles r on r.role_id = rr.role_id
order by
  r.role_code, rr.rule_id
;
