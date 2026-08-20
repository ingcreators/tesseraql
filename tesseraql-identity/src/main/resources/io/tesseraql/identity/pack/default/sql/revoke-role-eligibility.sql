-- Removes an eligibility. Any elevation already standing keeps its window: it was granted
-- while the eligibility held, and cutting it short here would be a revocation nobody asked
-- for -- the administrator who wants that ends it from the user's page.
delete from tql_role_eligibility
where
  user_id = /* userId */ 'u1'
  and role_id in (select role_id from tql_roles where role_code = /* roleCode */ 'x')
;
