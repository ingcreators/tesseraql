# Validation rule sets

> **Status: shipped.** `rules/*.yml` + `use:` references with the bind contract are resolved
> at manifest load (`ValidationRuleSets`; load errors `TQL-FIELD-4604..4608`, unreferenced-rule
> lint `TQL-FIELD-4612`), and the scaffolder is the first consumer: `scaffold crud` generates
> the per-unique-index `…IsFree` rule — shared by create and update, self-exclusion via a
> conditional directive so no null-typed bind ever reaches the database — referenced from both
> generated routes, with the dogfood suite exercising it end-to-end. Both remaining slices are
> now shipped: `scaffold crud` generates a `…Exists` rule per single-column foreign key
> (referenced by create and update, `when:`-guarded for nullable columns — the hook where
> "exists" grows into "exists and is active" in one file), and the purchase-request gallery
> app carries the hand-authored archetype: the `duplicateRequest` duplicate-application guard,
> whose SQL identifies the caller through the ambient `/* principal.loginId */` bind so the
> bind contract is just `[title]` — exercised by two declarative suite cases (violation and
> clean pass). This design is COMPLETE. User-facing docs:
> declarative-validation.md "Shared rule sets".

A **validation rule set** declares a `validate:` rule — a cross-field expression or a
validation SQL file — once, app-wide, for routes to reference by name. It is the `validate:`
layer's counterpart to [field domains](field-domains.md), with one structural difference that
shaped this design: a domain describes a field and is self-contained, while a rule binds route
inputs — so a shared rule carries a **bind contract**, and each route still wires its own
inputs to it. What is shared is the rule's SQL, its semantics, and its error identity; what
stays local is the wiring and the reporting target.

## The model

Rules live in `rules/` in the app home, next to `scope/` and `domains/`; each document holds a
map of named rules, files merge into one namespace, duplicates fail the load.

```yaml
# rules/inventory.yml
version: tesseraql/v1
rules:
  stockStaysNonNegative:
    file: validate-stock.sql        # relative to this rules document
    binds: [sku, delta]             # the bind contract a reference must satisfy
    code: insufficient-stock
    message: inventory.adjust.insufficient
  editableStatus:
    rule: "document.status == 'draft'"
    code: not-editable
    message: common.not-editable
```

A route references a rule with `use:` and supplies its own wiring:

```yaml
validate:
  stockCheck:
    use: stockStaysNonNegative
    params: { sku: params.sku, delta: params.delta }
    field: delta                    # the reporting target is this operation's input
    when: params.delta < 0          # guards stay local too
```

Resolution happens at manifest load (the domains precedent): the resolved route carries plain
`ValidationRule`s, so execution, the `TQL-FIELD-4220` error model, validation coverage, and
testing consume what they consume today, unchanged.

- **Shared keys** (the rule itself): `rule:`/`file:`, `binds:`, `code`, `message` defaults.
- **Local keys** (this operation's use): `params:` (checked against `binds:` — a missing or
  extra bind fails the load), `field:`, `when:`, and `code`/`message` overrides.
- Ambient [`principal.*` binds](ambient-params.md) seed shared-rule SQL exactly as they seed
  route SQL — a tenant-scoped uniqueness rule writes `/* principal.tenantId */` and drops
  `tenantId` from its bind contract entirely.

## Why now: the scaffolder is the first consumer

The gallery survey found **zero** hand-written duplicate rules — rules are operation-specific
until an app grows pairs. But the scaffolder generates exactly such a pair for every table:
create and update. Two generated slices give the mechanism its first real consumers, the same
"generator adopts first" pattern that shipped field domains:

1. **Scoped uniqueness per unique index** — today the scaffolder maps unique indexes to
   constraint-catalog entries (violation → field error after the write). A generated rule set
   adds the *pre-write* check both routes share, with self-exclusion on update:
   `uniqueName(binds: [name, excludeId])` — create wires `excludeId` to nothing (the rule's
   SQL treats null as "exclude nobody"), update wires `params.id`. One rule, two consumers,
   from day one of a scaffold.
2. **FK existence with business filtering** — for each foreign key, a generated
   `<refEntity>Exists` rule (`select 1 where not exists (…)`), the hook where "exists" grows
   into "exists and is active" by editing one SQL file instead of N.

## The hand-authored archetypes (why an app keeps writing these)

The LOB patterns that recur across a real application's routes, in roughly the order they
appear as an app grows — each is one `rules/` entry referenced by many routes:

- **Posting-period / close checks** — "the posting date falls in an open period", needed by
  every posting route in anything accounting-shaped.
- **Effective-period overlap** — price lists, shifts, reservations: "no overlapping period for
  the same key", shared by create, update, and bulk import.
- **Status-based editability** — "editable only while draft", repeated by every mutation route
  of the same entity (header update, line add, line delete, submit) when the document is not on
  the managed [workflow](approval-workflow.md).
- **Balance / quota checks** — stock, credit, budget: consume and cancel/return routes share
  the rule with opposite signs; in-transaction pre-write SQL is exactly the right execution
  point.
- **Dependent-master consistency** — "the unit belongs to the selected product": every form
  carrying the pair.
- **Duplicate-application windows and child-count caps** — "one leave request per period",
  "max N lines per order": child-create routes checking parent aggregates.

The gallery gains one archetype as a worked example (a posting-period or overlap rule in
purchase-request), so the docs page has a hand-authored reference next to the generated ones.

## Lint and tooling

- Lint family (registry-assigned at implementation): unknown `use:` reference and bind-contract
  mismatch fail the load; unreferenced rule (warning); a route-local rule textually identical
  to a shared one (warning — the copy-paste this replaces).
- The docs portal's Domains page pattern extends naturally: a Rules section listing each rule,
  its contract, and the routes referencing it.
- Validation coverage counts resolved per-route rules exactly as today; a shared rule
  referenced by two routes is two coverage entries, which is the honest reading.

## Out of scope

- **Rule composition/inheritance** — flat, like domains.
- **Sharing `when:` guards or `field:` targets** — both are operation semantics; a rule set
  that dictated them would be wrong in the second consumer.
- **Cross-app rule sets** — bundled/mounted apps keep their own namespaces.

## Open questions

1. Should `binds:` carry types (`binds: {sku: string, excludeId: integer}`) so the load can
   type-check the wiring against the referencing route's (domain-resolved) inputs? Leaning
   yes-but-later: names-only ships first, the type layer rides once domains give inputs stable
   types.
2. Does the generated uniqueness rule replace the constraint-catalog entry or complement it?
   Leaning complement: the pre-write rule gives the friendly 422, the catalog keeps the
   post-write race honest (the constraint still fires under concurrency).
