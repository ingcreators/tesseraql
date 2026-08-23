-- PostgreSQL and MySQL pagination; Oracle and SQL Server ride the .oracle/.sqlserver variants
-- (offset/fetch). The offset arrives precomputed because MySQL refuses expressions there.
select
  g.group_id    as "id",
  g.group_name  as "displayName",
  g.external_id as "externalId"
from tql_groups g
order by g.group_id
limit /* count */ 100 offset /* offset */ 0
