-- Lifts one context condition off a role. Removing the last condition of a kind removes
-- that requirement entirely, which is the intended way to widen a role again: the grant
-- itself is untouched, so nobody gains or loses a role here.
delete from tql_role_conditions
where role_id in (select role_id from tql_roles where role_code = /* roleCode */ 'x')
  and condition_kind = /* conditionKind */ 'network'
  and value = /* value */ '10.0.0.0/8'
;
