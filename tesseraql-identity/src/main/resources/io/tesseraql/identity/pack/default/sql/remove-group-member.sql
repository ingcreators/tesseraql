delete from tql_user_groups
where user_id = /* userId */ 'u1'
  and group_id in (select group_id from tql_groups
                   where group_code = /* groupCode */ 'OPS')
;
