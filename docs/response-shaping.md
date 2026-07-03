# Response shaping

Roadmap Phase 41: three declarative response tools on `query-json`/`query-html` routes, so
common shaping never needs a template or an extra endpoint.

## Computed fields

Every leaf of `response.json.body` (and every `response.html.model` value) is an expression
in the [core expression language](declarative-validation.md#the-expression-language-roadmap-phase-40)
— a plain dotted path behaves exactly as before, and computed leaves come for free:

```yaml
response:
  json:
    body:
      rows: sql.rows
      total: params.qty * params.price
      label: upper(trim(params.name))
```

Expressions compile at build time; a leaf the grammar rejects falls back to the legacy
dotted-path lookup, so existing bodies keep their behavior byte for byte.

## Nested composition (`nest:`)

A parent row set with a named child query composes into one document — grouped, not joined
by hand:

```yaml
sql:
  file: orders.sql          # parents
queries:
  lines:
    file: lines.sql         # children, e.g. where order_id in (...)
response:
  json:
    body:
      orders: sql.rows
    nest:
      - into: orders        # the body key holding the parent rows
        children: lines     # the named query whose rows attach
        as: lines           # the field added to each parent
        on: { id: order_id }  # parentColumn: childColumn
```

Join keys compare canonically (INTEGER 1 matches BIGINT 1); parents are copied, so shared
context rows are never mutated. `TQL-YAML-1019` keeps the references honest.

## Conditional statuses (`statusWhen:`)

Business conditions map to HTTP statuses declaratively — the generalization of
`expect.onMismatch`:

```yaml
response:
  json:
    body:
      data: sql.rows
    statusWhen:
      - when: sql.rowCount == 0
        status: 404
```

The first truthy arm wins (else the declared `status`). Works on `response.html` too;
conditions are pre-compiled at build (`TQL-YAML-1020`), and each arm's status rides into
the generated OpenAPI as a response entry.
