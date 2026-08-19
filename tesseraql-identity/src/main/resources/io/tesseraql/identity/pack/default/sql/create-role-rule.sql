insert into tql_role_rules (rule_id, role_id, enabled)
select /* ruleId */ 'rule1', r.role_id, 1
from tql_roles r
where r.role_code = /* roleCode */ 'orders.approver'
;
