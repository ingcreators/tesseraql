-- tesseraql-scaffold-checksum: sha256:cb5cd797ef9ceac6631cb6759332fc7b7cc09980e96ff81d50b7dca1c9459e86
-- Scaffolded single-row select for the items table.
select
  t.id,
  t.name,
  t.quantity,
  t.unit_price,
  t.due_date,
  t.active,
  t.note,
  t.version,
  t.created_by,
  t.created_at,
  t.updated_by,
  t.updated_at
from
  items t
where
  t.id = /* id */ 1
;
