# Organizational data scoping

> **Status: slices 1-2 delivered; slice 3 (masking integration) planned (roadmap Phase 29).** The scope
> document, the `/*%scope ... */` directive, additive role-conditional match arms, the lint, and the
> `data-scope` coverage kind are implemented. The shared org-unit foundation and masking integration
> are designed below but not yet built; those sections are written in design voice.

Organizational data scoping confines what rows a request can see, derived from the authenticated
**principal** (roles, groups, claims) rather than hand-written into every query. It is the row-level
complement to multi-tenancy: where tenancy isolates whole tenants, scoping restricts a caller to
their organizational reach *within* a tenant — their department, their region, their own records, or
(later) the subtree of org units they manage.

It builds on three existing subsystems:

- **The principal model** (`tesseraql-security` `Principal`) — `roles`, `groups`, `permissions`,
  and raw `claims`, populated identically by every auth mechanism (JWT, OIDC, SAML, API keys, mTLS).
- **2-way SQL** — a scope predicate is injected at a site the author chooses, parameterized, and
  stays runnable in a plain SQL tool. No query is rewritten behind the author's back.
- **Field policies / masking** (`FieldPolicyApplier`) — the same principal that drives scoping
  drives whether a field is shown, masked, or hidden (the masking integration is a later slice).

It deliberately mirrors the **tenant-predicate** mechanism (`tenant.id` binds plus the
`TQL-TENANT-3001` lint): same shape, one level deeper.

## The model

A **scope** is a named, reusable row-level predicate, declared once and applied to many queries.
At execution time TesseraQL resolves the scope against the principal and renders a parameterized
SQL predicate at the point the author marked with a `/*%scope ... */` directive.

Three invariants hold (the framework's extension principles):

1. **SQL-first, no hidden generation.** The author writes `/*%scope name */` where the predicate
   belongs; the engine never rewrites a `WHERE` clause or adds a table to the `FROM` clause. A scope
   that needs a join is written as a *correlated subquery* (`EXISTS`), never a top-level join — so
   row cardinality cannot change behind the query.
2. **Deny-by-default.** A principal that matches no arm of a scope sees *nothing* (`1=0`), never
   everything. Seeing all rows requires an explicit `apply: all` arm.
3. **Machine-checkable.** Scopes carry their own lint rules (`TQL-SCOPE-30xx`) and a `data-scope`
   coverage kind.

## Declaring a scope

A scope is a `kind: scope` document under `scope/`, indexed into the manifest like `consume/` and
`mcp/` documents. It is an **ordered list of match arms**: each arm pairs a principal condition
(`when`, a `Policy`-style role/permission/claim matcher) with an effect — `apply: all`,
`apply: none`, or a 2-way SQL predicate `file` (with its `params`).

```yaml
# scope/orders_scope.yml
version: tesseraql/v1
id: orders_scope
kind: scope
match:
  - when: { role: org-admin }                 # sees everything
    apply: all
  - when: { role: region-manager }            # sees their region(s)
    file: by_region.sql
    params:
      regions: principal.claim.regions
  - when: { permission: orders:read-own }     # sees only their own rows
    file: own_rows.sql
    params:
      uid: principal.subject
  # no matching arm ⇒ 1=0 (deny-by-default)
```

Each `file` is a 2-way SQL boolean predicate that stays runnable in a SQL tool:

```sql
-- scope/by_region.sql
$.region in /* regions */ ('R1','R2')

-- scope/own_rows.sql
$.created_by = /* uid */ 'u'
```

`when` reuses the authorization matcher used by route `policy:` and field masking (the
`Policy.Rule` shape): `{ role: … }`, `{ permission: … }`, or `{ claim: name, equals: value }`. It
intentionally does **not** use the 2-way expression language, which has no role-membership or
function-call syntax — role/permission/claim matching is the matcher's job, deny-by-default. An arm
with no `when` matches every principal.

### Composition is additive (OR)

When a principal matches **more than one arm**, the matching predicates are combined with `OR`: a
caller sees a row if *any* of their roles' scopes would show it — the same additive grant model as
`Policy.permits` (`anyOf`). An `apply: all` short-circuits to all rows; no match yields `1=0`.

| principal | rendered (`/*%scope orders_scope on o */`) | binds |
| --- | --- | --- |
| `org-admin` | `(1=1)` | `[]` |
| `region-manager` (regions R1,R2) | `((o.region in (?, ?)))` | `[R1, R2]` |
| `read-own` only | `((o.created_by = ?))` | `[uid]` |
| both manager and read-own | `((o.region in (?)) or (o.created_by = ?))` | `[R1, uid]` |
| neither | `(1=0)` | `[]` |

## The `/*%scope ... */` directive and joins

The author marks the injection site, so the engine never has to guess where the predicate goes. The
directive is a 2-way SQL comment (`/*% … */`, sibling to `/*%if … */`) followed by a **parenthesized
dummy predicate** — in a plain SQL tool it reads as `(1=1)`; at render time the resolved scope
predicate replaces the dummy.

```sql
select o.id, o.amount, c.name
from orders o
join customers c on c.id = o.customer_id
where o.status = /* status */ 'OPEN'
  and /*%scope orders_scope on o */ (1=1)     -- in a SQL tool this reads `and (1=1)`
order by o.id
```

- **Alias parameterization.** A fragment refers to its target table with the `$` sentinel
  (`$.region`); the call site supplies the alias with `on <alias>`, so one fragment is reusable
  across queries that alias the table differently. With no `on`, `$.` resolves to nothing
  (single-table queries). The alias must be a valid SQL identifier — it is the only string
  substitution, validated by the parser and lint, and is author-supplied at build time, never
  request input.
- **The scoped column may live in any joined table.** Place the directive and pass `on <thatAlias>`.
- **Two scoped tables** ⇒ two directives, each `on` its alias (possibly different scopes); they
  combine with `AND`.
- **Scopes that need a join** are correlated subqueries, not top-level joins:

  ```sql
  -- scope/orders_in_my_subtree.sql  (roadmap slice 2)
  exists (
    select 1 from tql_org_closure cl
    where cl.descendant = $.owner_unit
      and cl.ancestor  in /* my_units */ ('U1')
  )
  ```

Because the directive sits where a `WHERE` predicate goes, an accidental top-level join is a SQL
syntax error in a plain tool — the 2-way "runs in a SQL tool" property enforces invariant 1 for free.

Writes will be scoped the same way: a `/*%scope ... */` in the `WHERE` of an `UPDATE`/`DELETE` (a
later slice) confines the write to authorized rows.

## Org-unit hierarchy — a shared foundation (slice 2, delivered)

"My department and everything under it" needs an org-unit graph. Consistent with IAM's
managed/SQL-contract realm duality (`IdentityContracts`/`RealmConfig`), the org-unit model has two
modes, selected by `tesseraql.orgunit.mode`:

- **`managed`** — the runtime provisions and maintains two managed tables: `tql_org_unit` (units and
  their `parent_id` links) and `tql_org_closure` (the transitive closure — every ancestor/descendant
  pair, depth 0 being the unit itself). The `OrgUnitStore` (`tesseraql-core` SPI,
  `JdbcOrgUnitStore` impl) maintains the closure: `upsert`/`delete` units, then `rebuildClosure()`
  recomputes the closure from the parent graph (in Java, so it is dialect-agnostic — no recursive
  CTE). A subtree scope is then a plain, portable SELECT against the closure:

  ```sql
  -- scope/orders_subtree.sql
  $.owner_unit in (select descendant_id from tql_org_closure
                   where ancestor_id in /* my_units */ ('U1'))
  ```

  ```yaml
  # scope/orders_subtree.yml — everyone is subtree-scoped; an org-admin bypasses
  id: orders_subtree
  kind: scope
  match:
    - when: { role: org-admin }
      apply: all
    - file: orders_subtree.sql           # unconditional arm: applies to every principal
      params:
        my_units: principal.claim.org_unit
  ```

  The principal's home unit(s) ride a claim (`principal.claim.org_unit`); the closure turns them into
  the full subtree. A principal with no unit claim resolves to an empty `in (…)` and sees nothing.

- **`app`** (default) — the application owns its own organization tables; a subtree scope is written
  against them with the scope-core directive, exactly as above but joining the app's own closure or a
  recursive view. Nothing managed is provisioned, so an existing app gains no tables until it opts in.

This org-unit model is deliberately factored as a **shared foundation, not a scoping-private
table**, because the next roadmap phase needs the same graph (see below). It is delivered in
Phase 29 and consumed unchanged by Phase 28 — `OrgUnitStore.descendants(...)` is the Java seam both
sides reuse.

### Relationship to Phase 28 (approval workflow)

Milestone M9 pairs approval workflow with org-scoped data, and the two are duals over one org graph:

- **Scope** maps `principal → predicate over data rows`. **Assignee resolution** (Phase 28) maps
  `document/task → set of principals`. Same graph, opposite direction — so the org-unit foundation
  here is the substrate for workflow assignee resolution, not a second org model.
- A workflow **task inbox** ("tasks I can act on") is just a `/*%scope ... */` applied to the task
  table; additive (OR) composition is exactly its semantics — direct assignee *or* a candidate group
  I belong to *or* a task delegated to me.
- A workflow **state transition** is a scoped write: a `/*%scope ... */` in the transition's
  `UPDATE` confines it to the documents the caller has authority over, complementing the transition's
  expression-language guard (the guard checks state-machine legality; the scope checks row authority).

Phase 29 therefore reserves the seams Phase 28 plugs into — the assignee-resolution contract shape,
candidate-group derivation, and the write-scope injection point — without implementing the workflow
engine itself.

## Masking integration (slice 3, planned)

Column-level, role-conditional masking already works today through `FieldPolicy.policy` (the field
is shown only when the principal satisfies a `Policy`). Phase 29 adds **row-level** masking: a
field is masked in rows *outside* the caller's scope. Rather than evaluate a predicate per row in
Java, the query selects the scope predicate as a boolean flag and the field policy keys off it:

```sql
select o.id, o.salary,
       /*%scope payroll_scope on o as boolean */ (1=1) as _in_scope
from   payroll o
```

```yaml
response:
  json:
    fields:
      salary: { mask: fixed, unmaskWhen: _in_scope }   # masked unless the row is in scope
```

This keeps masking SQL-first and reuses the existing `FieldPolicyApplier` resolution order.

## Governance and testing

Lint catches a misdeclared or unreferenceable scope before it ships (delivered in slice 1):

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-SCOPE-3011` | error | a `/*%scope name */` directive names a scope not declared under `scope/` |
| `TQL-SCOPE-3012` | error | a scope definition is malformed: an arm declares neither/both of `apply` and `file`, an unknown `apply` value, a missing fragment file, or a `when` setting more than one of role/permission/claim (or a `claim` with no `equals`) |
| `TQL-SCOPE-3013` | error | a directive's `on <alias>` is not a valid SQL identifier |
| `TQL-SCOPE-3020` | error | `tesseraql.orgunit.mode` is set to something other than `managed` or `app` |

The runtime fails closed: a directive rendered without a scope resolver configured is `TQL-SQL-2106`,
and a directive naming an undeclared scope is `TQL-SQL-2107` — a scope can never silently no-op.

Planned for later slices: `TQL-SCOPE-3001` (a scope-governed table queried with no scope predicate,
mirroring `TQL-TENANT-3001`), `TQL-SCOPE-3010` (a route's `scope:` field naming an undeclared scope),
and `TQL-SEC-4100` (a write route bypassing a governed scope without an explicit bypass policy).

The **`data-scope`** coverage kind declares one item per scope under `scope/`; a scope counts as
covered when a declarative suite exercises a route (or consumer) whose SQL applies it through a
`/*%scope name */` directive — the same SQL-file basis as route coverage. An app with no scopes
reports a 1.0 ratio. Gate it with `coverage.thresholds.data-scope`. (Per-role-path coverage —
`<scopeId>#<role>` — is a later refinement.)

## Delivery slices

Phase 29 ships in slices, each a reviewable PR with CI green:

1. **Scope core** (delivered) — `scope/` documents, the `/*%scope ... */` directive (parser/AST/
   renderer in `tesseraql-core`, dependency-free, expanded at execution through the `ScopeResolver`
   SPI), additive `match` arms, the `TQL-SCOPE-30xx` lint, and the `data-scope` coverage kind.
   Attribute-based scoping with no hierarchy; ships value alone.
2. **Shared org-unit foundation** (delivered) — the `managed`/`app` org-unit model (the duality
   above): managed `tql_org_unit`/`tql_org_closure` with an `OrgUnitStore` that maintains the
   closure, and subtree scopes that join it. Designed for Phase 28 to consume unchanged.
3. **Masking integration** (planned) — row-level `unmaskWhen` keyed off a scope flag column.
