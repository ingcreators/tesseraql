# Decision tables

> **Status: in progress.** Slice 1 is implemented: the `decisions/` documents
> (`DecisionSets`, load errors `TQL-DECISION-4700..4708`, with `domain:` references merged
> at load and row values checked against declared types and enums — `4708`), the `decide:`
> reference with load-time resolution and wiring-root checks, the core row evaluator
> (`DecisionTables`:
> `eq`/`between`/`in`/`bool` cells, `first`/`unique` hit policies, `onMiss`, the unique
> overlap check `4714`, runtime `4720`/`4721`), evaluation inside the command transaction
> before the workflow document loads, `decision.*` seeded under every statement's parameters
> (binds and `/*%if */`, unseeded-bind guard `4722`), the lints `4711`/`4715`/`4716`, the
> decisions JSON Schema with scaffolded `.vscode` association, and the YAML-surface/error
> reference pages. Slice 2 is implemented: the table-backed `source:` — one generated SELECT
> with a `(col IS NULL OR col ⟨op⟩ ?)` arm per mapped column, `in` via a normalized child
> table, `orgSubtree` via the managed org closure (with the `tesseraql.orgunit.mode: managed`
> lint `4717`), dated rows via `effective:` + `effectiveAt:` defaulting to `audit.now`,
> `ORDER BY priority` with a portable single-row fetch — plus `default:` outputs for table
> misses, the plain-identifier guard, runtime lookup failures `4723`, and the sidecar DDL
> lint `4710` (degrading when `.tesseraql/docs/schema.json` is absent, the ReleaseDiff
> contract). Slice 3 is implemented: consumption plumbing — command steps gain `when:`
> guards (a falsy guard skips the step and records `steps.<name>.skipped`; the
> single-statement `sql:` form rejects a guard), workflow transitions gain `decide:` with
> load-time resolution and `decision` joins the guard roots, `decision.*` references in step
> guards and transition guards are checked against declared `decide:` entries (`4711`), and
> the enum-output lints land: comparing an output to a value the decision cannot produce is
> `4713`, and a from-state whose equality-guarded transitions leave declared enum values
> unhandled is `4712` — the unhandled else caught at build. Slice 4a is implemented: a
> workflow transition's decisions now evaluate after the document binds and before the
> guard, so the wiring may read `document.*` (`resolveForWorkflow`); the purchase-request
> gallery app carries the worked archetype (`decisions/approval.yml` consumed by the submit
> transition's `decide:` + assignee resolver, exercised by the declarative suite); and the
> user-facing docs live in declarative-validation.md "Decision tables" and
> approval-workflow.md "Decision-driven routing". Slice 4b is implemented: `scaffold
> decision` generates the `decisions/` declaration and the typed backing-table migration
> from one contract (proven by loading the output through the manifest pass;
> `TQL-DECISION-4730` for a malformed request), with `scaffold crud` over the generated
> table as the maintenance surface — the generated write-time integrity rules stay a
> follow-up, since they only bite once those routes exist; and the docs portal gains a
> Decisions page beside Domains and Rules. **This design is COMPLETE**; open follow-ups:
> generated maintenance integrity rules, a `decide:` declarative-test target, YAML→table
> promotion tooling.

A **decision table** turns a combination of input conditions into declared output values:
the approval route for an amount and a department, the shipping fee for a weight and a
region, the handling priority for an item category and a customer class. TesseraQL already
shares *boolean* judgments app-wide — [validation rule sets](validation-rule-sets.md)
answer "is this operation allowed?", [workflow](approval-workflow.md) guards answer "is
this transition legal?" — but it cannot share a **value-producing** decision. That
knowledge lives today in per-route SQL `CASE` expressions and scattered conditionals,
restated and drifting exactly the way field constraints drifted before
[field domains](field-domains.md).

It builds on subsystems already in the framework:

- **[Validation rule sets](validation-rule-sets.md)** — the `rules/` + `use:` precedent: a
  shared definition with a declared contract, wired locally by each consumer, resolved at
  manifest load. Decisions follow the same reference grammar and the same load-time
  resolution.
- **[Field domains](field-domains.md)** — decision inputs and outputs reference domains,
  so an enum-typed output gives the compiler the full value space to lint against.
- **The core expression language** (`io.tesseraql.core.expr`) — whitelist-only,
  side-effect-free — evaluates YAML-backed rows and the consumer-side wiring expressions.
- **[Ambient parameters](ambient-params.md)** — `principal.*` binds seed decision inputs
  ("the caller's org unit") exactly as they seed route SQL.
- **[Organizational data scoping](data-scoping.md)** — the `OrgUnitStore` substrate
  resolves the `orgSubtree` match kind, so "the sales department" means the same subtree
  in a decision row as it does in a scope directive.
- **[Transactional writes](transactional-writes.md)** — a table-backed decision evaluates
  as one generated `SELECT` inside the operation's transaction, on the operation's
  datasource; a missed or ambiguous lookup is a typed error, the row-count-expectation
  discipline applied to reads.
- **Schema introspection** (`CatalogIntrospector`, the [documentation
  portal](documentation-portal.md) sidecar) — the build can check a table-backed
  decision's column mapping against the real DDL even though the rows are runtime data.
- **[Scaffolding](scaffolding.md)** — the generator is the first consumer, the pattern
  that shipped domains and rule sets.

## The model

Decisions live in `decisions/` in the app home, next to `scope/`, `domains/`, and
`rules/`; each document holds a map of named decisions, files merge into one namespace,
duplicates fail the load. A decision declares its **contract** — typed inputs, typed
outputs, a hit policy, and a miss policy — and its **source**: inline YAML rows, or an
app-owned table.

```yaml
# decisions/approval.yml
version: tesseraql/v1
decisions:
  approvalRoute:
    inputs:
      amount:   { domain: money }
      category: { type: string, match: in }
      dept:     { type: string, match: orgSubtree }
    outputs:
      route:    { type: string, enum: [manager, director, cfo] }
      requiredLevel: { type: integer }
    hitPolicy: first            # first | unique
    onMiss: error               # error | default
    rows:
      - when: { category: [office-supplies, books], amount: ">= 10000" }
        out:  { route: manager, requiredLevel: 1 }
      - when: { dept: sales, amount: "> 100000" }
        out:  { route: director, requiredLevel: 2 }
      - out:  { route: cfo, requiredLevel: 3 }    # no when: — the default row
```

A route evaluates a decision with a `decide:` block — the `validate:` reference form,
wired the same way:

```yaml
decide:
  approvalRoute:
    use: approvalRoute
    params:
      amount: params.total
      category: params.category
      dept: principal.orgUnit
```

The result is available downstream as `decision.approvalRoute.route` and
`decision.approvalRoute.requiredLevel` — in SQL binds, `/*%if … */` directives, step
`when:` guards, workflow guards, and assignee resolution (see
[Acting on the result](#acting-on-the-result)).

- **Shared keys** (the decision itself): `inputs:`, `outputs:`, `hitPolicy:`, `onMiss:` /
  `default:`, and the rows or the table mapping.
- **Local keys** (this operation's use): `params:` (checked against `inputs:` — a missing
  or extra input fails the load), and `effectiveAt:` for dated lookups (below).
- Wiring expressions use the core expression language, so a derived fact — "the caller
  holds the officer role" — is evaluated at the reference site and passed as a typed
  input. **Cells stay comparisons; derivation lives in the wiring.**

## Two sources, one contract

The contract is the reference surface; the rows have two homes, chosen per decision:

- **YAML rows** (`rows:`) — policy that changes with a release: approval routing rules,
  editability matrices. Versioned, diffed, linted at build time, tested like code.
- **An app-owned table** (`source:`) — data that business users maintain at runtime: rate
  tables, thresholds, assignment matrices. Rows are rows; the maintenance surface is a
  scaffolded CRUD route set or the Studio data browser.

A consumer never knows which source backs the decision it references — the duality
follows IAM's managed/SQL realm split (`RealmConfig`) and workflow's managed/app-table
`mode:`. Moving a decision from YAML to a table changes its declaration, not its
consumers; the YAML rows become the seed migration (a scaffolder concern, noted in
[Open questions](#open-questions)).

```yaml
  shippingFee:
    inputs:
      weight: { domain: weightKg }
      region: { type: string }
    outputs:
      fee:     { domain: money }
      carrier: { type: string }
    hitPolicy: first
    onMiss: error
    source:
      table: shipping_fee_rules
      match:
        weight: { between: [weight_min, weight_max] }
        region: { eq: region }                    # NULL cell = wildcard
      priority: priority                          # resolution order for hitPolicy: first
      effective: [valid_from, valid_to]           # optional dated rows
      set:                                        # match: in — normalized child table
        category: { table: shipping_fee_rule_categories, key: rule_id, value: category }
      outputs: { fee: fee, carrier: carrier }
```

Ambient `principal.tenantId` confines table rows per tenant exactly as it confines route
SQL — a `tenant` column in the mapping binds it implicitly.

The boundary rule of thumb, stated so the feature is not misused: **if the rows change
with a release, they are YAML; if business users maintain them at runtime, they are a
table.** A `decisions/` document is not a home for master data, and a master table whose
grouping is reused across decisions (an item-classification table) should stay a plain
master joined in the wiring, not an `in` cell that grew.

## The condition model: cells are comparisons

A row is the **conjunction** of its cells; alternatives are separate rows ordered by
priority. Five match kinds cover the LOB patterns:

| match | cell semantics | YAML row | table columns |
|---|---|---|---|
| `eq` (default) | equals; empty cell = wildcard | scalar or absent | one nullable column |
| `between` | inclusive range; open ends | `">= 10000"`, `"5..10"` | `min`/`max` nullable pair |
| `in` | membership in a small fixed set | list | normalized child table |
| `orgSubtree` | bound org unit is in the cell's subtree | unit id | unit-id column, resolved via `OrgUnitStore` |
| `bool` | true/false; empty = wildcard | boolean | nullable boolean |

Arbitrary expressions in cells are **rejected by design**, for two load-bearing reasons:
overlap and exhaustiveness of structured cells are computable (ranges intersect, sets
intersect, subtrees nest), and structured cells push down to plain SQL. A condition that
needs an expression is a derivation — evaluate it in the `decide:` wiring and pass the
result as an input.

## Evaluation

Both sources implement identical semantics:

- **YAML rows** evaluate in memory with the core expression evaluator at the same point
  in the request.
- **Table rows** evaluate as one generated `SELECT` — each cell contributes
  `(col IS NULL OR col ⟨op⟩ ?)`, `in` cells an `EXISTS` against the child table,
  `orgSubtree` a join through the org-unit closure, dated rows an `effective` window
  test — `ORDER BY priority`, in the operation's transaction, on the operation's
  datasource. The generated SQL is ordinary 2-way SQL, loggable and runnable in a SQL
  tool.

Evaluation happens once per operation, after field validation and before `validate:`
rules and write steps, so validation guards and steps can both consume the result.

- `hitPolicy: first` — the highest-priority matching row wins.
- `hitPolicy: unique` — more than one match is a runtime error (`TQL-DECISION-4720`), the
  ambiguity surfaced instead of silently resolved.
- `onMiss: error` — no matching row is a runtime error (`TQL-DECISION-4721`); `onMiss:
  default` requires a `default:` output map (YAML source: a final row with no `when:`).
  There is no silent null.
- `effectiveAt:` in the `decide:` block binds the dated-lookup reference instant —
  defaulting to the operation's `audit.now`, but wireable to a document date
  (`effectiveAt: params.postingDate`), which accounting-shaped decisions need.

## Acting on the result

The `decision.*` namespace joins `params.*`, `document.*`, and `principal.*` in the
expression and bind vocabularies. Branching stays at the declared conditional points —
there is no flow engine; the decision centralizes the judgment, each consumer declares
its branch:

- **SQL binds and directives** — `/* decision.approvalRoute.requiredLevel */1` as a bind;
  `/*%if decision.approvalRoute.route == 'manager' */ … /*%end*/` inside a statement.
- **Step selection** — transactional-write steps gain the `when:` guard `validate:`
  already has; "level 1 approves directly, others open a workflow" is two steps with
  complementary guards.
- **Workflow routing** — transitions declare `decide:` like routes do; guards select
  among declared transitions (`guard: "decision.approvalRoute.route == 'director'"`), and
  assignee-resolution SQL binds the outputs. Dynamic transition *targets* stay
  prohibited — the state machine remains deny-by-default and fully declared.
- **Validation wiring** — a `validate:` rule's `when:` or `params:` can consume outputs.

Because outputs can be enum-typed, the compiler knows the full value space and lints the
consumption side: an output value no declared branch handles (`TQL-DECISION-4712`), and a
guard testing a value the decision cannot produce (`TQL-DECISION-4713`). The unhandled
`else` — the classic scattered-conditional defect — becomes a build-time finding, a
guarantee no `CASE` expression offers.

## Integrity when the rows are data

Build-time row lints are impossible for a table source, so each check moves to the point
where it is decidable:

- **Build time** — the column mapping is checked against the introspected DDL (missing
  column, type mismatch, `between` pair with incompatible types: `TQL-DECISION-4710`);
  contract violations and unknown references fail the load like rule sets do.
- **Write time** — the scaffolded maintenance routes carry generated `validate:` rules:
  overlapping cells with equal priority for `unique` decisions, duplicate priority,
  inverted ranges, overlapping effective periods for the same key (the
  [rule-set archetype](validation-rule-sets.md) generated instead of hand-written). The
  integrity lint becomes a pre-write 422 at the data's entry point.
- **Run time** — miss and multi-hit are the typed errors above, never a null output.

YAML rows keep the full build-time treatment: overlap between rows of a `unique`
decision (`TQL-DECISION-4714`), an unreachable row shadowed by higher priority
(`TQL-DECISION-4715` warning), a `first` decision with no default row and `onMiss: error`
left implicit (info), and exhaustiveness over enum-typed inputs where the value space is
finite.

## Lint and tooling

- `decisions/*.yml` gets its own JSON Schema, wired into a scaffolded app's
  `.vscode/settings.json`, and the YAML-surface reference documents the `decide:` form.
- Load errors `TQL-DECISION-4700..4709` (duplicate name, unknown `use:`, wiring/contract
  mismatch, bad match kind, bad source mapping); build lints `4710..4719`; runtime
  `4720..4729`.
- The documentation portal gains a Decisions page next to Domains and Rules: contract,
  match kinds, referencing routes; YAML-backed decisions render their rows as the table
  they are, table-backed ones link the schema page of their backing table.
- Studio: YAML-backed rows get a table-shaped editor (the validation-builder precedent);
  table-backed rows reuse the data browser with a contract overlay — this column is the
  `weight` lower bound, that one the `fee` output.
- Declarative test suites cover decisions the way they cover rules: YAML-backed cases
  assert outputs for given inputs; table-backed cases seed rows through the existing
  fixture mechanism.

## Scaffolder and gallery adoption

The generator adopts first, as with domains and rule sets:

- `scaffold decision <name>` generates, for a table-backed decision: the typed backing
  table migration (columns from the contract — no generic EAV table), the maintenance
  CRUD routes with the write-time integrity rules, and the `decisions/` declaration.
- The purchase-request gallery app carries the YAML-backed archetype: `approvalRoute`
  feeding the workflow's guarded submit transitions and assignee resolution — the worked
  example for [Acting on the result](#acting-on-the-result).
- A second gallery app carries the table-backed archetype (a fee or threshold table with
  effective dating), so both sources have a hand-inspectable reference.

## Out of scope

- **Cell expressions** (DMN FEEL and kin) — forfeits computable overlap checks and SQL
  pushdown; derivations belong in the wiring.
- **A flow engine** — decisions produce values; branching stays at the declared
  conditional points.
- **Decision chaining** (a decision consuming another decision's output) — wirable
  manually today via `decide:` ordering; first-class support waits for a real consumer.
- **Caching** — the in-transaction `SELECT` is correct and simple; revisit with the
  caching phase, not before.
- **A generic managed rows table** — table-backed decisions get typed, app-owned tables;
  an EAV store would defeat both introspection lints and plain-SQL-tool readability.

## Open questions

- **Step `when:` scope** — introduced here for decision outputs, but the guard vocabulary
  naturally admits `params.*`/`principal.*` too; decide whether step guards launch
  restricted to `decision.*` or general.
- **YAML→table promotion** — should `scaffold decision --from <name>` emit the seed
  migration from existing YAML rows, and does the declaration keep a tombstone of the
  move?
- **Response shaping** — may `decision.*` feed response fields directly (echoing the
  resolved route in the API response), or only via SQL? Leaning SQL-only for v1.
- **Multi-datasource** — a decision table living on a different datasource than the
  consuming operation breaks single-transaction evaluation; v1 likely requires
  same-datasource and lints otherwise.
