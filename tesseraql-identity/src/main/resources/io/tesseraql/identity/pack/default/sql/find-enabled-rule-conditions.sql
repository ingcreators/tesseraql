select
  rr.rule_id       as rule_id,
  r.role_code      as role_code,
  c.attribute_name as attribute_name,
  c.match_kind     as match_kind,
  c.value          as value
from
  tql_role_rules rr
  join tql_roles r on r.role_id = rr.role_id
  left join tql_role_rule_conditions c on c.rule_id = rr.rule_id
where
  rr.enabled = 1
order by
  rr.rule_id
;
