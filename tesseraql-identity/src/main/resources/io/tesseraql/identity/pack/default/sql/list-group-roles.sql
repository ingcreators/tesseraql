-- What one group delivers: its roles with their application axis. A group is a bundle of
-- roles, so this is the other half of the group detail page.
select
  r.role_id     as role_id,
  r.role_code   as role_code,
  r.role_name   as role_name,
  r.application as application
from
  tql_group_roles gr
  join tql_roles r on r.role_id = gr.role_id
where
  gr.group_id in (select group_id from tql_groups
                  where group_code = /* groupCode */ 'OPS')
order by
  r.role_code
;
