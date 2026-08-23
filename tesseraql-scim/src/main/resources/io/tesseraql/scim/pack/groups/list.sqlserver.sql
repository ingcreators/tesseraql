select
  g.group_id    as "id",
  g.group_name  as "displayName",
  g.external_id as "externalId"
from tql_groups g
order by g.group_id
offset /* offset */ 0 rows fetch next /* count */ 100 rows only
