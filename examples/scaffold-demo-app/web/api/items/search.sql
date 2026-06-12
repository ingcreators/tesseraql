-- Starter search (tesseraql new); runnable as-is in a plain SQL tool, and the smoke
-- suite exercises both branches.
select
  i.id,
  i.name,
  i.quantity,
  i.due_date
from
  items i
where
  1 = 1
/*%if q != null && q != "" */
  and i.name like /* q */ 'First item'
/*%end*/
order by
  i.id
limit /* limit */ 50
offset /* offset */ 0
;
