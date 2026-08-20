-- Ends an elevation early. Keyed on the elevation provenance, so a standing admin grant
-- of the same role is never touched -- the same discipline the rule revoke uses.
delete from tql_user_roles
where
  user_id = /* userId */ 'u1'
  and role_id in (select role_id from tql_roles where role_code = /* roleCode */ 'x')
  and source = 'elevation'
;
