-- tesseraql-scaffold-checksum: sha256:aad24efe116bf3bb339d7bb5337145f97b1ae96f1db80511a754473f3bdb8910
-- Scaffolded insert for the items table: audit columns stay explicit in the SQL (Phase 18).
insert into items (
  name,
  quantity,
  unit_price,
  due_date,
  active,
  note,
  version,
  created_by,
  created_at,
  updated_by,
  updated_at
) values (
  /* name */ 'sample',
  /* quantity */ 1,
  /* unitPrice */ 1,
  /* dueDate */ '2026-01-01',
  /* active */ true,
  /* note */ 'sample',
  1,
  /* audit.user */ 'someone',
  /* audit.now */ '2026-01-01 00:00:00',
  /* audit.user */ 'someone',
  /* audit.now */ '2026-01-01 00:00:00'
)
