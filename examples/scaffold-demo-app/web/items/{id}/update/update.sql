-- tesseraql-scaffold-checksum: sha256:c600ec88574eeef9091cc154d823d76f5bf87079d1817c69287ffbb186f2a554
-- Scaffolded update for the items table: the SET list advances the version, the lock directive compares it (docs/edit-conflict.md).
update items
set
  name = /* name */ 'sample',
  quantity = /* quantity */ 1,
  unit_price = /* unit_price */ 1,
  due_date = /* due_date */ '2026-01-01',
  active = /* active */ true,
  note = /* note */ 'sample',
  version = version + 1,
  updated_by = /* audit.user */ 'someone',
  updated_at = /* audit.now */ '2026-01-01 00:00:00'
where
  id = /* id */ 1
  and /*%lock*/ (1=1)
