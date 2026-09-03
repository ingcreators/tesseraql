# Transactional writes

`command-json` — the recipe a route file declares for a JSON write endpoint (see
[application layout](app-layout.md) for how routes and their recipes are laid out) — executes
one business operation, not one statement. A command may declare an ordered list of SQL steps
that run in a single transaction, allocate gapless document numbers, capture generated keys
for later steps, stamp audit columns from canonical binds, turn silent lost updates into
`409 Conflict`, and map constraint violations to field-level errors. Every step stays a plain,
SQL-tool-runnable [2-way SQL](two-way-sql.md) file.

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
    items:
      fields:                       # the line's own input contract
        productId: { type: integer, required: true }
        quantity:  { type: integer, required: true, min: 1 }

steps:
  - id: orderNo
    sequence: order-number          # managed document-number sequence
  - id: header
    sql:
      file: insert-order.sql
      keys: [id]                      # capture the generated key
      params:
        orderNo: steps.orderNo.value
        customerId: body.customerId
  - id: lines
    sql:
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
| `steps.<name>.out.<name>` | the OUT parameter values of a `mode: call` step |
| `outbox.eventId` | the outbox event id, when the route declares `outbox:` |

Steps default to `mode: update`. A step references only request sources and *earlier* steps;
forward references fail at route build time (`TQL-ROUTE-3102`). A command with one statement is
a one-step pipeline like any other — there is no second spelling — so it publishes
`steps.<id>.affectedRows` under whatever id it was given.

### Generated keys

`keys: [id]` retrieves the inserted key per the dialect capability matrix: PostgreSQL and
Oracle honor requested column names (`RETURNING` / `RETURNING INTO`); MySQL and SQL Server
return the auto-increment/identity value, which is mapped to the first declared key. Keys
are read from the first inserted row.

### Stored calls

`mode: call` runs a stored procedure or function through the driver's call interface. The
statement is ordinary 2-way SQL — the JDBC call escape or the dialect's native syntax — and
an OUT parameter is a bind site in the reserved `out.` namespace, typed by an `out:`
declaration on the step:

```yaml
steps:
  - id: reprice
    sql:
      file: reprice-order.sql
      mode: call
      params: { orderId: path.id }
      out: { new_total: numeric }
```

```sql
{call reprice_order(/* orderId */'o-1', /* out.new_total */null)}
```

The rendered positions do the bookkeeping: every bind site whose expression starts with
`out.` is registered as an output of the declared type instead of binding a value, and the
values come back as `steps.<name>.out.<name>` for later steps and the response. The
declaration is all-or-nothing per execution: a rendered `out.*` the step does not declare,
or a declared name the statement never renders, is refused naming the mismatch. `expect:`
and `keys:` do not apply — a call answers no affected-row count. On PostgreSQL the JDBC
driver's `escapeSyntaxCallMode` connection property decides whether `{call …}` invokes a
function (the default) or a procedure; that is the driver's documented contract, set on the
datasource where it matters.

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

Each `line.*` bind is the element value the input contract produced, typed: declare the
element's fields under `items.fields:` and the array validates like every other input, with
violations addressing themselves as `lines[2].quantity`
([declarative validation](declarative-validation.md#line-items-the-contract-inside-an-object-array)).

### Embedded variables (dynamic identifiers)

A bind renders a `?` placeholder, which is only valid where a *value* goes — never an
identifier. A dynamic `ORDER BY` column, sort direction, or table name uses a
`/*# template */` embedded variable instead; the syntax and its safety rules live in
[2-way SQL](two-way-sql.md#embedded-variables-dynamic-identifiers). What matters on a command
route is the allowlist: every placeholder fed from request input must resolve to an
`enum`-validated input, or the build fails (`TQL-SQL-2109`) — the runtime's rejection of SQL
meta-characters (`TQL-SQL-2108`) is only defense in depth behind it:

```yaml
input:
  sort: { type: string, enum: [id, name, created_at], default: id }
  dir:  { type: string, enum: [asc, desc], default: asc }
sources:
  main:
    sql:
      file: search.sql
      params: { sort: query.sort, dir: query.dir }
```

The CRUD scaffold ([scaffolding](scaffolding.md)) uses exactly this for its sortable list
datagrid.

The whole header+lines shape ships runnable in the procurement gallery app
(`examples/procurement-app`, `POST /api/requisitions`): a generated-key header step, a
`%for` detail insert bound to the request's `lines` array, an `items.fields:` contract on
each line, and a `validate:` rule (`params.lines.size > 0`) refusing an empty order —
suite-covered, so the pattern in this page is proven, not illustrative.

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

## The declared lock

A command route names the column that guards its write, and the framework compares it:

```yaml
version: tesseraql/v1
id: items.update
kind: route
recipe: command-json
lock: { column: version, type: integer }
input:
  id:   { domain: items.id, required: true }
  name: { domain: items.name, required: true }
steps:
  - id: main
    sql:
      file: update.sql
      mode: update
      params: { id: params.id, name: params.name }
```

The statement marks where that comparison belongs, and keeps advancing the column itself:

```sql
update items
   set name = /* name */'',
       version = version + 1
 where id = /* id */0
   and /*%lock*/ (1=1)
```

`/*%lock*/ (1=1)` renders as `and (version = ?)`, bound to the value the caller sent back, and
reads as `(1=1)` in a plain SQL tool. The framework never writes the SET list, so the column
can be a counter the statement bumps, a timestamp it stamps, or a value the database maintains
— the comparison is the same either way. The directive must appear exactly once across the
route's steps, and never inside `/*%if*/` or `/*%for*/`: a lock that can render away is not a
lock.

`lock:` implies `expect: { rowCount: 1, onMismatch: conflict }` on the step whose statement
carries the directive. Declaring both on that step is refused, and so is a `lock:` no statement
carries (`TQL-ROUTE-3119`). Every other step of a multi-step command is untouched.

The value travels as `_lock`, a framework-owned request field. A browser form carries the
hidden input the form pattern renders ([declarative-views.md](declarative-views.md)); an API
caller sends it in the body, and both `_lock` and `_overwrite` are emitted into OpenAPI as part
of what the route accepts. It never reaches `input:` binding, so no `writable:`, policy or mask
can drop it — and an `input:` field named for the lock column is refused, because one column
cannot have two owners.

**The declared type is a bound, not a hint.** Every form value arrives as a string, so an
untyped lock on a numeric column would compare `"3"` against an integer. `integer`, `number`,
`string` and `date` survive the round trip through a hidden field; `datetime` is refused at
build, because a rendered row carries an ISO instant and the input coercion reads a
space-separated pattern back. The bare form `lock: version` is the opaque one, compared exactly
as it arrived.

A stale save rolls the transaction back and answers `409 Conflict` (`TQL-SQL-4094`), with a
`details.lock` sibling naming the column and the two fields:

```json
{"error": {"code": "TQL-SQL-4094", "message": "Conflict",
  "details": {"conflict": {"step": "main", "expectedRows": 1, "actualRows": 0,
      "hintKey": "tql.conflict.stale",
      "hint": "The record may have been changed or deleted by another user; reload it and retry the operation"},
    "lock": {"column": "version", "field": "_lock", "overwriteField": "_overwrite"}}}}
```

`_overwrite` waives the comparison for one request: the directive renders as `(1=1)` and every
other predicate still stands, so a waived save against a deleted row — or one the caller's
scope excludes — refuses again. It is a waiver, never a re-read: the framework does not fetch
the contested row and renders nothing from it. A request carrying neither field answers `400`
(`TQL-FIELD-2011`). Browsers get the two choices as a dialog and as a full page
([hypermedia-ui.md](hypermedia-ui.md#edit-conflict)).

## Row-count expectations

`expect:` is the hand-authored form of the same guard, and it stays legal. It counts affected
rows and does not know why a count was wrong, so the version predicate and its bind are the
author's to write and to keep paired.

```yaml
steps:
  - id: main
    sql:
      file: update-status.sql
      mode: update
      expect:
        rowCount: 1
        onMismatch: conflict   # the default; `error` yields a 500 instead
      params:
        id: body.id
        status: body.status
        version: body.version
```

`expect:` is a step key. Declared under `sources:` it is refused at build time
(`TQL-ROUTE-3120`), because a read acquisition counts no affected rows and the declaration
would silently do nothing.

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
  "details": {"conflict": {"step": "sql", "expectedRows": 1, "actualRows": 0,
    "hintKey": "tql.conflict.stale",
    "hint": "The record may have been changed or deleted by another user; reload it and retry the operation"}}}}
```

The hint resolves through the message catalog with the request locale
([internationalization.md](internationalization.md)); `hintKey` keeps the stable key.

Lint keeps the two halves paired on any step that does not carry a lock directive: an
UPDATE with `expect.rowCount` but no version-column predicate warns `TQL-SQL-2104`; a version
predicate without `expect.rowCount` warns `TQL-SQL-2105`. On a route that declares `lock:` they
run against the declared column, because the other steps of a multi-step command write as
unguarded as any. On the carrying step the compiler has already answered the pairing question,
and two warnings it cannot answer take over. A
lock column the SET list never advances (`TQL-SQL-2116`) matches on every save, which is
silently last-write-wins; a `/*%lock*/` outside the statement's `WHERE` (`TQL-SQL-2117`) is not
a predicate at all.

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
  "details": {"fields": [{"field": "customerId", "code": "unknown-customer",
              "constraint": "orders_customer_fk"}]}}}
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

Commands compose with the existing idempotency machinery: declare `idempotency:` and send a
key — a replay returns the stored response without re-executing any step, so a
double-submitted order form writes once. Two transports carry the key, header first: an API
caller sends the `Idempotency-Key` header, and a browser form echoes the `_idempotency`
hidden field the framework mints into every rendered form (one fresh key per rendered form
instance; the field is reserved, so it never reaches input binding). The no-JS full-page
POST follows the same branches — nothing here is JavaScript.

```yaml
idempotency:
  required: true    # the Idempotency-Key header becomes mandatory (default: optional)
  scope: orders     # key namespace; defaults to the route id
  ttl: 24h          # how long a stored response replays; defaults to 24h
```

The presence of the block enables idempotency; every key is optional. Reusing a key for a
*different* request answers `422` (`TQL-IDEM-4221`) — same intent token plus different content
is a stale tab or a bug, not a retry. Reusing it while the first request is still in flight
answers `409 Conflict` (`TQL-IDEM-4090`). The request hash that decides "different" covers the
JSON body, or a browser form's fields in canonical order, with the authenticated principal
folded in — one user's key never replays for another.

The key is spent by the *commit*, not the attempt. A refusal before commit — a validation
failure, a constraint violation, any error — releases the claim, so the corrected resubmit
with the same key can commit. Only a stored success replays.

## Error codes

| Code | Status | Meaning |
| --- | --- | --- |
| `TQL-SQL-4092` | 409 | row-count expectation failed (`onMismatch: conflict`) |
| `TQL-SQL-4094` | 409 | a declared `lock:` refused a stale write |
| `TQL-FIELD-2011` | 400 | a locked route reached with neither `_lock` nor `_overwrite` |
| `TQL-SQL-2115` | 500 | a lock directive rendered with no lock value seeded |
| `TQL-SQL-2602` | 500 | row-count expectation failed (`onMismatch: error`) |
| `TQL-SQL-2610` | 500 | document-sequence allocation failed |
| `TQL-SQL-2611` | 500 | sequence step without a configured allocator |
| `TQL-ROUTE-3102` | — | invalid steps declaration (route build time) |
| `TQL-ROUTE-3119` | — | invalid `lock:` declaration (route build time) |
| `TQL-ROUTE-3120` | — | `expect:` or `keys:` under `sources:` (route build time) |
| `TQL-SQL-2104` / `2105` | — | lint: optimistic-locking pairing nudges |
| `TQL-SQL-2116` / `2117` | — | lint: a lock the statement never advances / a `/*%lock*/` outside the `WHERE` |
| `TQL-FIELD-2009` | — | lint: a command step declares `enrich:`, which has no rows to fold into |

## Next

- [declarative-validation.md](declarative-validation.md) — refusing a bad command before it writes.
- [notifications.md](notifications.md) — telling someone the command succeeded.
- [response-shaping.md](response-shaping.md) — what the command answers with.
