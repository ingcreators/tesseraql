select
  c.rule_id        as rule_id,
  c.attribute_name as attribute_name,
  c.match_kind     as match_kind,
  c.value          as value
from
  tql_role_rule_conditions c
order by
  c.rule_id, c.attribute_name, c.value
;
