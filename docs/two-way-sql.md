# 2-way SQL

Every SQL file in a TesseraQL application is *2-way*: the same file is, at once, a complete
SQL statement you can paste into any SQL client and run as-is, and a parameterized template
the framework binds at runtime. Nothing is generated behind your back — what you author is
what executes, with directive comments swapped for real values.

The trick is that every directive lives inside a standard SQL block comment, and every bind
site is followed by a *dummy value*. A plain SQL tool skips the comments and runs the dummies;
the framework strips the comments, drops the dummies, and binds real request values in their
place as prepared-statement parameters:

```sql
select u.id, u.name, u.status
from users u
where 1 = 1
/*%if q != null && q != "" */
  and u.name like /* q */ '%sato%'
/*%end*/
order by u.id
```

Pasted into `psql`, this searches for `%sato%`. Executed by the framework, `'%sato%'` is gone,
`?` takes its place, and the caller's `q` is bound — or the whole `and` clause is omitted when
`q` is absent. One file serves authoring, ad-hoc debugging, and production.

Because SQL lives in files rather than strings, it is also data the tooling can work with:
lint checks it, test suites measure statement and branch coverage over it
(`tesseraql coverage`, [getting-started.md](getting-started.md)), and the documentation portal
renders it.

## Where SQL files live

SQL files are colocated with the route that uses them and referenced relatively via
`sql.file:` (or a step's / validation rule's `file:`) — see [app-layout.md](app-layout.md):

```text
web/api/users/
  get.yml          # sql: { file: search.sql, params: { q: query.q } }
  search.sql
```

A sibling `<base>.<vendor>.sql` (for example `search.mysql.sql`) replaces `<base>.sql` when the
app runs against that database vendor, so a dialect-specific rewrite stays a per-file concern.

## Bind values

`/* name */ dummy` marks a bind site. The dummy may be a quoted string, a number, or a bare
word; it exists only so the file runs in a plain tool and is never sent to the database. At
runtime the site becomes a single `?` parameter:

```sql
insert into products (sku, name, stock, reorder_level)
values (/* sku */ 'XX-0', /* name */ 'Example',
        /* stock */ 0, coalesce(/* reorder_level */ 10, 10))
```

The names available to bind are the ones the route declares under `params:`, each mapped to a
dotted source path:

```yaml
sql:
  file: search.sql
  params:
    q: query.q
    limit: query.limit
```

Sources resolve against the request context:

| Source | Meaning |
| --- | --- |
| `params.*` / `query.*` | the declared, coerced and validated inputs (`params` and `query` name the same map) |
| `body.*` | the raw request body (form or JSON) |
| `path.*` | path parameters (typed when declared under `input:`) |
| `principal.*` | the authenticated caller (`subject`, `loginId`, `roles`, …) |
| `tenant` | the resolved tenant |
| `steps.<name>.*` | in a command step: an *earlier* step's result — generated keys, affected rows, an allocated sequence value ([transactional-writes.md](transactional-writes.md)) |

A bind expression may itself use a dotted path to navigate into a bound value — for example
`/* line.productId */` inside a loop over `lines`. Values bind with their runtime types: the
declared `input:` type decides what the driver receives (an `integer` input binds as an
integer, a `date` as a date), so the dummy's job is purely to keep the file runnable — though
matching its shape to the real type keeps tool runs representative.

### IN-list binds

A bind followed by a parenthesized dummy group expands to one `?` per element of a collection:

```sql
select * from users where id in /* ids */ (1, 2, 3)
```

With `ids = [10, 20, 30]` this renders `id in (?, ?, ?)`. An empty collection renders
`in (null)` — valid SQL that matches no rows — and a non-collection value fails with
`TQL-SQL-2001`.

## Conditional blocks

`/*%if expr */ … /*%end*/` includes its fragment only when the condition holds, with optional
`/*%elseif expr */` and `/*%else */` branches:

```sql
select * from t
/*%if kind == "a" */ where a = 1
/*%elseif kind == "b" */ where b = 2
/*%else */ where c = 3
/*%end*/
```

Conditions use the core expression language — the same whitelist-only language as
[declarative validation](declarative-validation.md): comparisons, `&&`/`||`/`!`, literals,
dotted paths over the bound names, and the whitelisted functions (the built-ins plus any
[custom functions](declarative-validation.md#custom-functions) installed from the app's
modules). There are no method calls and no side effects. A bare value is truthy when it is
non-null (a `Boolean` counts as itself), so `q != null && q != ""` is the idiomatic guard for
an optional text filter. The `where 1 = 1` anchor keeps the statement valid in both a plain
tool and every rendered variant.

Each `if`/`elseif` branch is a coverage branch: test suites report which variants of a
statement were exercised.

## Loops

`/*%for item : items */ … /*%end*/` repeats its fragment once per element. An optional
separator is emitted *between* iterations, and it lives inside the directive comment — so a
variable-length multi-row insert stays one SQL-tool-runnable statement:

```sql
insert into order_lines (order_id, line_no, product_id, quantity)
values
/*%for line : lines separator ', ' */
(/* orderId */1, /* line_index */0 + 1, /* line.productId */10, /* line.quantity */1)
/*%end*/
```

The loop exposes `<item>_index` (0-based) alongside `<item>`. The separator must be a quoted
literal, for example `separator ','`.

## Embedded variables (dynamic identifiers)

A bind renders a `?`, which is only valid where a *value* goes — never a column name, sort
direction, or table. For identifier-position fragments, use an embedded variable:
`/*# template */`, whose `{placeholder}` references are interpolated into the SQL *text* at
render time instead of bound:

```sql
select * from items t
where 1 = 1
/*# order by t.{sort} {dir}, t.id */   -- a plain tool skips this and runs unordered
limit 50
```

The whole fragment lives inside the comment, so the file stays runnable. Because the value is
written into SQL text, it must be constrained to a safe set: every placeholder that comes from
request input has to resolve to an `enum`-validated input, or lint fails the build
(`TQL-SQL-2109`). As defense in depth the runtime also rejects any resolved value carrying SQL
meta-characters (quotes, `;`, comment markers, control characters) with `TQL-SQL-2108` — but
the `enum` allowlist is the real guarantee. See the worked sortable-list example in
[transactional-writes.md](transactional-writes.md).

## Ambient binds

Two bind namespaces resolve from the request context without being declared under `params:`.

**Audit binds** — in command routes, `/* audit.user */` and `/* audit.now */` carry the caller's
identity (login id, falling back to the subject) and a single clock reading per command, so every
statement in one transaction stamps the same instant:

```sql
update orders
set status = /* status */'APPROVED',
    updated_by = /* audit.user */'someone',
    updated_at = /* audit.now */'2026-01-01 00:00:00'
where id = /* id */1
```

**Principal binds** — in any authenticated statement (query, command step, named query, or
validation SQL), the `principal.*` namespace binds the authenticated caller directly, replacing
the `actor: principal.loginId` / `tenantId: principal.tenantId` wiring that would otherwise be
restated per route:

```sql
select sku, qty
from   products
where  tenant_id = /* principal.tenantId */'t-demo'
```

The namespace is **closed and read-only**: exactly `subject`, `loginId`, `tenantId`, `roles`,
`permissions`, and `groups` resolve (the list namespaces bind as IN-lists like any declared list
parameter). There is no raw-claim passthrough — a claim goes through explicit `params:` wiring
where it is visible and reviewable. `audit.user` remains the blessed spelling in write
statements' audit columns.

Everything stays explicit in the SQL — nothing is injected behind the template's back. The bind
name `audit` is reserved (declaring it under `params:` fails at route build time); a declared
parameter named `principal` shadows the ambient namespace entirely, so explicit wiring always
wins. A route without an authenticated principal seeds nothing: a `principal.*` bind on a public
route fails loudly as an unbound parameter instead of binding null.

## The scope directive

`/*%scope name */ (1=1)` marks where a row-level access predicate belongs. In a plain tool the
parenthesized dummy reads as `(1=1)`; at runtime the named scope — declared once under
`scope/` — expands to a parameterized predicate derived from the caller, with `on <alias>` to
qualify the column in a join and `as boolean` to render a per-row flag instead of a filter.
The full model, examples, and its lint rules are in [data-scoping.md](data-scoping.md).

Two lints guard the principal namespace: a `principal.*` bind on a route that never carries an
authenticated principal is an error (`TQL-SEC-4136`), and a `params:` entry that merely renames
an ambient field draws a nudge toward the ambient spelling (`TQL-SEC-4137`).

## Staying tool-runnable

A few rules keep every file executable as-is:

- **Every `/* … */` block comment is a directive.** Use `--` line comments for remarks.
- **Every bind carries a dummy** so the raw statement has a value in that position; a scope
  directive carries a parenthesized dummy predicate.
- **Loop separators live in the directive**, never as trailing text between fragments.
- **Don't author `LIMIT`/`FETCH` on a paginated route** — the framework appends the dialect's
  pagination clause at execution time, and `TQL-YAML-1018` warns when the file carries its own
  ([pagination.md](pagination.md)).
- A file that does not parse as a 2-way template fails at build/serve time with
  `TQL-SQL-2102`, naming the offending line.

## What lint checks

`tesseraql lint` (and the same engine in Studio and the editor extension) verifies SQL usage
statically:

| Code | Meaning |
| --- | --- |
| `TQL-SQL-2103` | a route, step, or validation rule references a missing SQL file |
| `TQL-SQL-2104` | an UPDATE declares `expect.rows` but has no version-column predicate (optimistic locking half-wired) |
| `TQL-SQL-2105` | an UPDATE has a version predicate but no `expect.rows` (a stale edit would silently affect zero rows) |
| `TQL-SQL-2109` | an embedded variable interpolates request input that is not `enum`-constrained |
| `TQL-YAML-1018` | a paginated route's SQL carries its own `LIMIT`/`FETCH` |
| `TQL-SCOPE-3011` / `3013` | a scope directive names an undeclared scope / an invalid `on` alias ([data-scoping.md](data-scoping.md)) |

Validation SQL has one extra rule: it must be a SELECT (it runs inside the command's
transaction and must not write) — see [declarative-validation.md](declarative-validation.md).

## Where to go next

- [transactional-writes.md](transactional-writes.md) — multi-step commands, generated keys,
  audit binds in context, optimistic locking, constraint mapping
- [declarative-validation.md](declarative-validation.md) — SQL-backed validation rules that
  answer field-scoped errors
- [pagination.md](pagination.md) — offset and keyset pagination over plain-runnable queries
- [data-scoping.md](data-scoping.md) — the `/*%scope */` directive and organizational
  row-level access
