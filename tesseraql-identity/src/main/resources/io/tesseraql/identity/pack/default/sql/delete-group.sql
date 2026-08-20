-- Deletes the group row itself. The caller empties the memberships and the role bundle
-- first, in that order, so no join table is left pointing at a group that is gone.
delete from tql_groups
where group_code = /* groupCode */ 'OPS'
;
