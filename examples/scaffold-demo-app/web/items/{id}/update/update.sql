-- tesseraql-scaffold-checksum: sha256:a5b45c89486a0c71b10a78597804fdea93621d673ef62fb7a8fdb4a1688b849c
-- Scaffolded update for the items table: the version predicate pairs with expect.rows (Phase 18).
update items
set
  name = /* name */ 'sample',
  quantity = /* quantity */ 1,
  unit_price = /* unitPrice */ 1,
  due_date = /* dueDate */ '2026-01-01',
  active = /* active */ true,
  note = /* note */ 'sample',
  version = version + 1,
  updated_by = /* audit.user */ 'someone',
  updated_at = /* audit.now */ '2026-01-01 00:00:00'
where
  id = /* id */ 1
  and version = /* version */ 1
;
