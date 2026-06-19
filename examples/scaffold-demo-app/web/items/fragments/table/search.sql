-- tesseraql-scaffold-checksum: sha256:2860b84dd2c5412422a8d3c53f86868462ebb268a88ef05ac66ffdfd25bee865
-- Scaffolded search for the items table. The WHERE filter runs as-is in a plain SQL tool; the ORDER BY is
-- resolved by the engine from the sort/dir inputs — the column allowlist is
-- baked in below, so an unknown sort value falls back to the primary key.
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
order by
/*%if sort == "name" */
  t.name
/*%end*/
/*%if sort == "quantity" */
  t.quantity
/*%end*/
/*%if sort == "unit_price" */
  t.unit_price
/*%end*/
/*%if sort == "due_date" */
  t.due_date
/*%end*/
/*%if sort == "active" */
  t.active
/*%end*/
/*%if sort == "note" */
  t.note
/*%end*/
/*%if sort != "name" && sort != "quantity" && sort != "unit_price" && sort != "due_date" && sort != "active" && sort != "note" */
  t.id
/*%end*/
/*%if dir == "desc" */
  desc
/*%end*/
limit 50
;
