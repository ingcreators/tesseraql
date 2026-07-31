# Workflow expressiveness — SQL guards, decision stamps, one-action dispatch

> **Status: design.** Nothing here is implemented. Three related workflow-surface gaps the
> procurement demo (docs/procurement-demo.md) forced into app-side idioms; this document
> designs the declarative replacements. Grounded throughout in what
> `examples/procurement-app` must write today.

## The three frictions, as shipped code

**1. Guards cannot ask the database.** The guard language is a deliberate whitelist over
`document.*` / `task.*` / `principal.*` / `decision.*` — no subqueries, by design. But real
transitions gate on set conditions: "every line is priced", "a shipment is registered".
The demo ships both workarounds:

- *Denormalized counters*: `quotes.total_lines` / `priced_lines` exist **only** so the
  submit guard can be a column expression; the pricing surface maintains them with a
  second step (`refresh-counter.sql`) on every write. Two extra columns, one extra
  statement per price, and an invariant an author must remember.
- *The zero-row command*: `order-ship.sql` demands `exists (select 1 from shipments …)` in
  its WHERE, so an unregistered ship updates nothing and fails with the generic
  `TQL-WORKFLOW-3204`. Correct — but the caller learns "Conflict", not "no shipment is
  registered", and the intent hides in a SQL comment instead of the workflow document.

**2. Decision outputs are stamped by hand.** The decide-then-branch pattern appears three
times (requisition, order, and the negotiation): the transition evaluates a decision, the
command's only real job is `set approval_route = /* decision.approvalRoute.route */`, and
later transitions guard on the stamped column. The command file is boilerplate; forgetting
the stamp (or clearing it on rework) is a silent logic bug the framework could own.

**3. Lane branching leaks into the client.** Every decision-routed fork ships as a
guard-complementary transition *pair* — `approve`/`advance`, `issue`/`approve_issue`,
`propose_accept`/`propose_review` — and the **caller must know which id to call**: the
tour scripts pick the right endpoint by out-of-band knowledge, and a UI would need the
same conditional. The state machine knows exactly which one is legal; the surface makes
the client guess.

## Design

### 1. SQL guard files: `guard: { file: …, code: …, message: … }`

A transition's `guard` gains a file form, the exact shape `assign:` and scope fragments
already use — a 2-way SQL **boolean predicate** evaluated in the transition's transaction,
after `decide:` resolution, before the expression guard would run:

```yaml
# workflow/order.yml — what the demo's ship gate becomes
- id: ship
  from: confirmed
  to: shipped
  security: { auth: bearer, policy: sup.act }
  guard:
    file: shipment-registered.sql        # SELECT returning a row = pass, no row = fail
    code: shipment-not-registered        # rides the 422 payload like a validation code
    message: procurement.ship.unregistered   # optional messages/ key
  command: order-ship.sql
```

```sql
-- workflow/shipment-registered.sql — runnable in a SQL tool, like every guard input
select 1 from shipments where order_id = /* key */ 'ORD-0'
```

- **Semantics**: rows ⇒ pass; zero rows ⇒ the transition fails `422` with the declared
  `code` (default `TQL-WORKFLOW-3202`, same status as an expression guard) — the caller
  finally learns *why*. `document.*`, `decision.*`, `principal.*`, and ambient binds are
  in scope exactly as in a command.
- **String and file forms coexist**: `guard: "document.amount > 0"` stays; a map with
  `file:` selects the SQL form; declaring both is a lint error. The expression form
  remains the right tool for column checks — the file form exists for set conditions.
- **What it deletes from the demo**: `total_lines`/`priced_lines`, `refresh-counter.sql`,
  the two-step pricing route (back to one statement), and the `exists` clauses inside
  `order-ship.sql`/`start-quote.sql`-style commands whose WHERE currently doubles as an
  undeclared guard. The `/*%state … */`-shaped cross-document checks (a rule reading
  `tql_workflow_instance`) become guard files too — with a lint (below) checking the
  `doc_type` literal.
- **Coverage/lint**: guard files join route coverage on the same SQL-file basis; a guard
  file that is not a pure SELECT is a build error; `TQL-WORKFLOW-31xx` gains "guard file
  missing/not-a-query". A string literal compared against `wi.doc_type` that names no
  declared workflow `document.type` becomes a warning (the typo that today survives to
  runtime), in guard files, rules, and route SQL alike.

### 2. Decision stamps: `stamp:`

The decide-then-persist idiom becomes a declaration on the transition:

```yaml
- id: submit
  from: draft
  to: submitted
  decide:
    approvalRoute:
      use: approvalRoute
      params: { amount: document.amount, category: document.category }
  stamp:
    approval_route: decision.approvalRoute.route   # column := expression, same tx
  command: submit.sql                              # now optional: audit-only or gone
  assign: { file: approver.sql }
```

- The engine issues `UPDATE <document.table> SET <column> = ? WHERE <key> = ?` for the
  stamped columns in the transition's transaction, **before** the author command — so a
  command (if any) and later guards read the stamped value. Paths are whitelisted to
  `decision.*`, `document.*`, `principal.*`, and literals; columns must be plain
  identifiers (the decision `source:` guard's precedent).
- `stamp: { approval_route: null }` on the rework transition declares the clearing that
  today hides in `order-rework.sql` — the stamp/unstamp pair becomes visible structure
  the enum-exhaustiveness lints (`TQL-DECISION-4712/4713`) can reason about end to end.
- **What it deletes from the demo**: `submit.sql` and `order-submit.sql` shrink to
  audit-note commands or disappear; the "forgot to clear the lane on rework" bug class
  goes with them.

### 3. One-action dispatch: `dispatch:`

A named action whose target the engine picks by evaluating the declared transitions'
guards — the client calls one endpoint, the state machine chooses the lane:

```yaml
dispatch:
  # POST /api/orders/{key}/submit_decision picks the transition whose guard holds.
  - id: submit_decision
    oneOf: [issue, approve_issue]
```

- **Legality**: every member must share `from` (lint), and their guards must be provably
  disjoint where the framework can prove it — guards over one enum-typed decision output
  or one stamped column reuse `TQL-DECISION-4712`'s exhaustiveness machinery: full cover
  + pairwise disjoint = clean; anything else is a warning naming the overlap. At runtime,
  guards evaluate in declaration order; the first that holds fires; none ⇒ `422` with the
  per-member codes in the payload. Two holding (possible only past a warning) ⇒ first
  wins, logged.
- The synthesized endpoint carries the members' **common** security spec (differing
  specs are a lint error — a dispatch is one action, one audience). Members stay
  individually callable; the demo keeps `approve`/`advance` for the tour's teaching value
  but the UI calls `submit_decision`.
- **What it deletes from the demo**: the client-side lane knowledge in all three pairs;
  the tour script's "call advance for two_stage, approve for manager" footnotes.

## What this deliberately does not do

- **No BPMN/route-builder ambitions**: `dispatch:` selects among *declared* transitions;
  it introduces no new control flow. The three features together keep the workflow yml
  the single artifact a reviewer reads.
- **No guard side effects**: guard files must be queries; writes stay in commands. The
  render path enforces it (query mode), not convention.
- **The zero-row command contract stays**: `TQL-WORKFLOW-3204` remains the row-authority
  backstop; guard files reduce how often apps *reach* it for data-state checks, they do
  not replace it for scope authority.

## Migration and slices

Purely additive; no existing workflow changes meaning. Suggested slices:

1. **Guard files** — parse + render + runtime evaluation + `transition:` suite-target
   support + lints (not-a-query, missing file) + docs; convert the quote submit guard in
   procurement-app and delete the counters (the archetype in one PR).
2. **`stamp:`** — engine UPDATE + path whitelist + lints + rework-clearing; convert the
   three stamp commands.
3. **`dispatch:`** — synthesis + disjointness lint + endpoint; convert the tour.
4. **`doc_type` literal lint** — independent, small, lands whenever.

Each slice re-proves the procurement suite (40 cases) unchanged except the deletions it
enables — the demo is the fixture the design is measured against.

## Open questions

- Should a guard file's `message:` key double as the `dispatch:` none-held explanation
  (concatenated per member), or does dispatch need its own message?
- `stamp:` writes bypass the command's `/*%scope */` — the engine stamps by key after the
  state advance already established row legality. Is that authority story crisp enough,
  or should stamps render through the same scope directive machinery? (Leaning: the
  advance's optimistic `WHERE current_state = from` plus the transition's security is the
  authority; a scope on a by-key UPDATE the engine issues adds noise, not safety. To be
  revisited against the threat model before slice 2.)
