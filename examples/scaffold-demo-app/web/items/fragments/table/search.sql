-- tesseraql-scaffold-checksum: sha256:add62b35902c511a5abdae35ecad3dfd00f5e649e5bafd2a6e2c79aa51deaaa8
-- Scaffolded search for the items table; runnable as-is in a plain SQL tool.
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
  t.id
limit 50
;
