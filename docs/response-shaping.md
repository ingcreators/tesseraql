# Response shaping

Response shaping gives `query-json`/`query-html` routes three declarative tools —
computed fields, nested composition, and conditional statuses — so common shaping never
needs a template or an extra endpoint.

## Computed fields

Every leaf of `response.json.body` (and every `response.html.model` value) is an expression
in the [core expression language](declarative-validation.md#the-expression-language)
— a plain dotted path behaves exactly as before, and computed leaves come for free:

```yaml
response:
  json:
    body:
      rows: sql.rows
      total: params.qty * params.price
      label: upper(trim(params.name))
```

Expressions compile at build time; a leaf the grammar rejects is treated as a plain
dotted path, so existing bodies keep their behavior byte for byte.

## Nested composition (`nest:`)

A parent row set with a named child query composes into one document — grouped, not joined
by hand:

```yaml
sql:
  file: orders.sql          # parents
  params: { customerId: query.customerId }
queries:
  lines:
    file: lines.sql         # children
    params: { customerId: query.customerId }
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

The child is an ordinary named query: it runs with the route's own inputs — nothing binds
the parent rows' keys into it automatically — and the grouping happens in memory on the
`on:` columns after both queries return. Scope the child's SQL so it returns the children
of exactly the parents the main query returns; here both files filter on the same input:

```sql
-- lines.sql
select l.order_id, l.line_no, l.product_id, l.quantity
from order_lines l
join orders o on o.id = l.order_id
where o.customer_id = /* customerId */1
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

## Where to go next

- [pagination.md](pagination.md) — the `page` context entry maps into shaped bodies the
  same way (`meta: page`)
- [declarative-views.md](declarative-views.md) — declarative HTML rendering over the same
  route declarations
