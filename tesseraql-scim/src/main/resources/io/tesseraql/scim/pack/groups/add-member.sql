-- Deliberately no exists-guard on the user: provisioning order is the client's, and a group
-- pushed before its members' user records must not fail the whole create.
insert into tql_user_groups (user_id, group_id, source, starts_at, ends_at)
values (/* memberId */ 'u-1', /* groupId */ 'g-1', 'scim', null, null)
