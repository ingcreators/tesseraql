-- tesseraql-scaffold-checksum: sha256:5370a3af3b152e964ea471c95c51cec7ab252b96944c6b4b3c7459c2b488a199
-- Scaffolded update for the items table: the version predicate pairs with expect.rows (Phase 18).
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
  and version = /* version */ 1
