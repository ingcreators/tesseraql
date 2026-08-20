delete from tql_group_roles
where group_id in (select group_id from tql_groups
                   where group_code = /* groupCode */ 'OPS')
;
