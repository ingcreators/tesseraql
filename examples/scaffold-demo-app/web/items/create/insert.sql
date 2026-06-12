-- tesseraql-scaffold-checksum: sha256:08bcecf8cfdde760e9f1d857a6c25aa540d0f0de7f54898cd5052cf953d22d94
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
);
