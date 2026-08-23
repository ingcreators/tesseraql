select
  g.group_id    as "id",
  g.group_name  as "displayName",
  g.external_id as "externalId"
from tql_groups g
where g.group_id = /* id */ 'g-1'
