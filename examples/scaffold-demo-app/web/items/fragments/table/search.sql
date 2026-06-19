-- tesseraql-scaffold-checksum: sha256:26cdf0bb0c98a97503d2bee610de9d2595905ee00762c9ea97c37a08a8a961be
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
limit 50
;
