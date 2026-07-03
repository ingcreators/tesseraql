-- tesseraql-scaffold-checksum: sha256:3454ed22b380bd177a737b0e793df457842087c6122267bf2d6b2a320c1d42be
-- Scaffolded search for the items table; runnable as-is in a plain SQL tool. The ORDER BY lives in an
-- embedded variable, applied at render time from the sort/dir inputs (an enum
-- allowlist), so a plain tool runs the base query unordered.
select
  t.id,
  t.name,
  t.quantity,
  t.unit_price,
  t.due_date,
  t.active,
  t.note
from
  items t
where
  1 = 1
/*%if q != null && q != "" */
  and t.name like /* q */ 'sample'
/*%end*/
/*# order by t.{sort} {dir}, t.id */
;
