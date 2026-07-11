# Transactional writes

`command-json` executes one business operation, not one statement. A
command may declare an ordered list of SQL steps that run in a single transaction, allocate
gapless document numbers, capture generated keys for later steps, stamp audit columns from
canonical binds, turn silent lost updates into `409 Conflict`, and map constraint violations
to field-level errors. Every step stays a plain, SQL-tool-runnable 2-way SQL file.

## Steps: one transaction, many statements

```yaml
version: tesseraql/v1
id: orders.create
kind: route
recipe: command-json

input:
  customerId:
    type: integer
    required: true
  lines:
    type: array

steps:
  orderNo:
    sequence: order-number          # managed document-number sequence
  header:
    file: insert-order.sql
    keys: [id]                      # capture the generated key
    params:
      orderNo: steps.orderNo.value
      customerId: body.customerId
  lines:
    file: insert-lines.sql
    params:
      orderId: steps.header.keys.id # bind a value produced by an earlier step
      lines: body.lines

response:
  json:
    status: 201
    body:
      orderId: steps.header.keys.id
      orderNo: steps.orderNo.value
```

Steps execute in their authored order on one connection; on any failure the whole
transaction rolls back. Each step publishes its result into the execution context:

| Context path | Meaning |
| --- | --- |
| `steps.<name>.affectedRows` | rows affected by an `update` step |
| `steps.<name>.keys.<column>` | generated keys captured via `keys:` |
| `steps.<name>.rows` / `rowCount` | result of a `query` step |
| `steps.<name>.value` | the allocated value of a `sequence:` step |
| `outbox.eventId` | the outbox event id, when the route declares `outbox:` |

Steps default to `mode: update`. A step references only request sources and *earlier* steps;
forward references fail at route build time (`TQL-CAMEL-3102`). The single-statement `sql:`
form is unchanged and still publishes `sql.affectedRows` (and `sql.eventId` with an outbox).

### Generated keys

`keys: [id]` retrieves the inserted key per the dialect capability matrix: PostgreSQL and
Oracle honor requested column names (`RETURNING` / `RETURNING INTO`); MySQL and SQL Server
return the auto-increment/identity value, which is mapped to the first declared key. Keys
are read from the first inserted row.

### Multi-row inserts

The `%for` directive accepts a separator, so a variable-length detail insert stays one
statement and the raw template remains runnable in a plain SQL tool (the separator lives
inside the directive comment). The loop exposes `<item>_index` (0-based):

```sql
insert into order_lines (order_id, line_no, product_id, quantity)
values
/*%for line : lines separator ', ' */
(/* orderId */1, /* line_index */0 + 1, /* line.productId */10, /* line.quantity */1)
/*%end*/
```

### Embedded variables (dynamic identifiers)

A `/* expr */` bind becomes a `?` placeholder — safe, but a placeholder is only valid where a
*value* goes, never an identifier. For an identifier-position fragment a bind cannot drive — a
dynamic `ORDER BY` column, sort direction, or table name — use a **`/*# template */` embedded
variable** (Doma-style). Its `{placeholder}` references are interpolated into the SQL *text* at
render time, not bound. Keep the whole fragment inside the comment so the statement stays runnable
in a plain SQL tool (the comment is skipped, the base query runs):

```sql
select * from items t
where 1 = 1
/*# order by t.{sort} {dir}, t.id */   -- applied at render time; a plain tool runs it unordered
limit 50
```

Because the value is written into SQL text, it **must** be constrained to a safe set: every
placeholder has to resolve to an `enum`-validated input, or the build fails (`TQL-SQL-2109`). The
renderer additionally rejects a resolved value carrying SQL meta-characters (`TQL-SQL-2108`) as
defense in depth, but the `enum` allowlist is the real guarantee:

```yaml
input:
  sort: { type: string, enum: [id, name, created_at], default: id }
  dir:  { type: string, enum: [asc, desc], default: asc }
sql:
  file: search.sql
  params: { sort: query.sort, dir: query.dir }
```

The CRUD scaffold ([scaffolding](scaffolding.md)) uses exactly this for its sortable list
datagrid.

## Audit binds

`/* audit.user */` and `/* audit.now */` resolve from the authenticated principal (login id,
falling back to the subject) and a single clock reading per command, so every statement in
the transaction stamps the same instant. Audit columns stay explicit in the SQL — nothing is
injected behind the template's back:

```sql
insert into orders (order_no, customer_id, status, version, created_by, created_at)
values (/* orderNo */1, /* customerId */1, 'PLACED', 1,
        /* audit.user */'someone', /* audit.now */'2026-01-01 00:00:00')
```

The bind name `audit` is reserved; declaring it under `params:` fails at route build time.

## Row-count expectations (optimistic locking)

```yaml
sql:
  file: update-status.sql
  mode: update
  expect:
    rows: 1
    onMismatch: conflict   # the default; `error` yields a 500 instead
  params:
    id: body.id
    status: body.status
    version: body.version
```

```sql
update orders
set status = /* status */'APPROVED',
    version = version + 1,
    updated_by = /* audit.user */'someone',
    updated_at = /* audit.now */'2026-01-01 00:00:00'
where id = /* id */1
  and version = /* version */1
```

When the statement affects a different number of rows, the transaction rolls back and the
route answers `409 Conflict` (`TQL-SQL-4092`) with a usable hint:

```json
{"error": {"code": "TQL-SQL-4092", "message": "Conflict",
  "conflict": {"step": "sql", "expectedRows": 1, "actualRows": 0,
    "hintKey": "tql.conflict.stale",
    "hint": "The record may have been changed or deleted by another user; reload it and retry the operation"}}}
```

The hint resolves through the message catalog with the request locale
([internationalization.md](internationalization.md)); `hintKey` keeps the stable key.

Lint keeps the two halves paired: an UPDATE with `expect.rows` but no version-column
predicate warns `TQL-SQL-2104`; a version predicate without `expect.rows` warns
`TQL-SQL-2105`.

## Constraint-violation mapping

Map database constraint names to field-level errors so a unique or foreign-key violation
surfaces as something a form can render, not an opaque 500:

```yaml
errors:
  constraints:
    orders_customer_fk:
      field: customerId
      code: unknown-customer
    uq_users_email:
      field: email          # code defaults from the violation kind, e.g. `duplicate`
```

```json
{"error": {"code": "TQL-SQL-4091", "message": "Conflict",
  "fields": [{"field": "customerId", "code": "unknown-customer",
              "constraint": "orders_customer_fk"}]}}
```

A mapping may declare its own `message:` key; without one, the built-in
`tql.constraint.<code>` texts localize the standard codes
([internationalization.md](internationalization.md)).

Unmapped violations still classify portably across dialects: unique `TQL-SQL-4090` (409),
foreign key `TQL-SQL-4091` (409), not-null `TQL-SQL-4001` (400), check `TQL-SQL-4002`
(400), serialization `TQL-SQL-4093` (409).

htmx callers (`HX-Request: true`) receive the same details as the Hypermedia Components
field-errors fragment instead of JSON (a conflict hint renders as the alert body):

```html
<div class="hc-alert" data-variant="error" role="alert" data-hc-field-errors
     data-error-code="TQL-SQL-4091">
  <p class="hc-alert__title">Conflict</p>
  <ul class="hc-alert__errors">
    <li class="hc-alert__error" data-field="customerId" data-code="unknown-customer">…</li>
  </ul>
</div>
```

## Document-number sequences

`sequence: <name>` allocates from the managed `tql_doc_sequence` table (created by the `V2`
framework migration, or on first use). The allocation is gapless with row-lock semantics:
the incrementing `UPDATE` runs on the command's connection and holds the sequence row's lock
until the transaction ends, so concurrent allocations serialize and a rollback returns the
number. Sequences are created on first use, starting at 1.

Because allocation serializes writers per sequence name, reserve gapless numbers for
documents that need them (invoices, vouchers); use database identities for plain surrogate
keys.

## Idempotent replay

Commands compose with the existing idempotency machinery: declare `idempotency:` and send an
`Idempotency-Key` header — a replay returns the stored response without re-executing any
step, so a double-submitted order form writes once.

## Error codes

| Code | Status | Meaning |
| --- | --- | --- |
| `TQL-SQL-4092` | 409 | row-count expectation failed (`onMismatch: conflict`) |
| `TQL-SQL-2602` | 500 | row-count expectation failed (`onMismatch: error`) |
| `TQL-SQL-2610` | 500 | document-sequence allocation failed |
| `TQL-SQL-2611` | 500 | sequence step without a configured allocator |
| `TQL-CAMEL-3102` | — | invalid steps declaration (route build time) |
| `TQL-SQL-2104` / `2105` | — | lint: optimistic-locking pairing nudges |
