-- Re-syncs the mutable profile of a federated user from the IdP's latest assertion
-- (docs/application-roles.md structural decision 3): the identity link is the immutable
-- key, so login id, display name and email may all move. Display name and email are only
-- written when the IdP mapped and sent them.
update tql_users set
/*%if displayName != null */
  display_name = /* displayName */ 'Alice Example',
/*%end*/
/*%if email != null */
  email = /* email */ 'alice@example.com',
/*%end*/
  login_id = /* loginId */ 'alice'
where
  user_id = /* userId */ 'u1'
;
