# Ambient parameters

> **Status: design.** This document precedes implementation. Nothing described here is part of
> the released YAML surface yet; it is the agreed target for review.

An **ambient parameter** is a SQL bind resolved from the authenticated request context rather
than from per-route `params:` wiring. TesseraQL already has two ambient families: the canonical
audit binds `/* audit.user */` and `/* audit.now */`
([transactional writes](transactional-writes.md)), and the `tenant.id` bind of
[multi-tenancy](multi-tenancy.md). This design generalizes the precedent into a declared
**`principal.*` namespace**, because the wiring it replaces is the single most repeated text in
the route corpus: across the gallery apps, `roles: principal.roles` appears thirty-seven times,
`actor: principal.loginId` twenty-seven, `tenantId: principal.tenantId` eighteen — plus naming
variants (`principalRoles:`) that are drift in the making. Two of those three are audit and
tenant-isolation fields, where a forgotten line is not noise but a hole.

## The model

2-way SQL gains a fixed, read-only bind namespace resolved from the authenticated principal:

```sql
UPDATE products
SET    qty        = qty + /* delta */1,
       updated_by = /* principal.loginId */'someone',
       updated_at = /* audit.now */'2026-01-01 00:00:00'
WHERE  sku       = /* sku */'DEMO-1'
  AND  tenant_id = /* principal.tenantId */'t-demo'
```

No `params:` entry is needed for the `principal.*` binds; `delta` and `sku` are wired exactly as
today. The namespace is closed:

| Bind | Value | Shape |
| --- | --- | --- |
| `principal.subject` | stable subject identifier | scalar |
| `principal.loginId` | login id | scalar |
| `principal.tenantId` | tenant claim | scalar |
| `principal.roles` | role names | list (IN-list / array bind) |
| `principal.permissions` | permission names | list |
| `principal.groups` | group names | list |

`audit.user` and `audit.now` remain as they are — `audit.user` is definitionally
`principal.loginId`, and the audit spelling stays the blessed form in write statements, matching
every existing example and the scaffolder's output.

## Invariants

1. **SQL-first, visible at the use site.** An ambient bind is written in the SQL text with a
   dummy value, exactly like every other 2-way SQL bind — the query stays runnable in a plain SQL
   tool, and nothing is injected behind the author's back. This is the same invariant
   [data scoping](data-scoping.md) holds for `/*%scope … */`.
2. **Closed, read-only namespace.** Only the fields in the table above resolve. There is no
   expression evaluation inside a bind comment, no `claims.*` passthrough — a raw-claim need goes
   through explicit `params:` wiring where it is visible and reviewable.
3. **No principal, no compile.** A `principal.*` bind in a route whose effective auth is `public`
   is a compile-time error (`TQL-PRINCIPAL-*` family; numbers assigned at implementation). The
   binder never silently binds null for an absent principal.
4. **Explicit wiring still wins.** A route-local `params:` entry with the same SQL parameter name
   shadows the ambient resolution for that statement. `params:` remains the mechanism for
   renames, non-principal values, and anything computed.

## What this replaces, and a nudge

Existing `params:` lines of the form `x: principal.y` keep working; ambient binds make them
unnecessary rather than invalid. An info-level lint flags a `params:` entry that is a pure rename
of an ambient field, pointing at the ambient spelling — a migration nudge, not a rule. The
scaffolder and the Studio SQL builder emit ambient binds directly; the naming-variant drift
(`principalRoles:` vs `roles:`) disappears because the SQL names the field itself.

Scope predicates are unaffected: `/*%scope … */` arms already resolve the principal internally.
Ambient binds cover the hand-written statements *outside* scope arms — audit columns, tenant
predicates in single-purpose queries, role checks in validation SQL.

## Testing and coverage

Route tests already execute under a declared test principal; ambient binds resolve from it the
same way audit binds do today, so existing fixtures carry over. Validation coverage and the SQL
lint treat an ambient bind as a bound parameter — a route whose SQL references
`principal.tenantId` under an auth mode that cannot produce a tenant claim fails the same
tenant lint (`TQL-TENANT-3001` neighborhood) that catches a missing tenant bind today.

## Out of scope

- **Request-scoped non-principal ambience** (`request.locale`, `request.ip`). Tempting, but each
  has an existing home (i18n resolution, access logs) and none shows repetition in the corpus.
- **Ambient values in `validate:` expressions or templates.** The expression language and
  templates already receive the principal explicitly; this design is only about SQL binds.

## Open questions

1. Should list binds (`principal.roles`) be permitted in `IN (...)` positions only, mirroring
   how existing list params are constrained, or anywhere an array type is legal on the target
   database? Leaning: exactly the existing list-param rule, no special case.
2. Is `principal.` the right prefix, or should the namespace unify with the existing families as
   `ctx.principal.*` / `ctx.audit.*`? Leaning: keep `principal.` — `audit.*` and `tenant.id`
   are established, and a rename buys uniformity at the cost of touching every example.

## Related designs

[Field domains](field-domains.md) shares field-level knowledge; [route
defaults](route-defaults.md) shares per-route security settings. The three are independent
slices and ship separately.
