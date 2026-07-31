# Transition engine — one pipeline, invoked everywhere

> **Status: slices 1–2 implemented.** Slice 1: `TransitionExecutor` in
> `tesseraql-yaml`; `TransactionalCommandProcessor` and `TestRunner.fireTransition`
> delegate; `ColumnWorkflowStore` moved beside it; `inlineRoutes` pinned at both
> `restConfiguration()` sites. Slice 2: engine-level dispatch — the governed dispatch
> route invokes members' own command processors directly with **typed** `3201`/`3202`
> fall-through (no shadow routes, no body matching), dispatch-level `decide:`
> (inherit-if-absent, alias collisions lint as `3112`), `attempted[].code`/`guard` in
> the none-held `422`, and the winner named as `transition` in the success payload.
> One refinement against the original track B text: the selection is **sequential
> full-pipeline attempts, each in its own transaction** — not one shared transaction —
> because track A deliberately left tasks/history/notify in the route layer, and a
> single-transaction selector would have had to re-implement exactly that tail. The
> refusal-leaves-nothing-behind property held either way (guards run before the
> advance); what one-transaction would have added (no inter-attempt race window) the
> conditional advance already turns into the member's own `409`. Slice 3: the
> `dispatch:` suite target — the selection loop (dispatch-level `decide:` once, then
> members over the executor, refusals rolling back to savepoints) in the case's
> rolled-back transaction; the procurement `propose` pair's duplicated decide collapsed
> into its dispatch. Note the standing limitation the suite cases inherit: documents
> start at the initial state (mid-flow fixtures remain the retrospective's open item
> ⑦), so gallery dispatch cases assert refusal aggregation; the winner paths are
> engine-tested in `DispatchCaseTest`. Slice 4: lint `TQL-WORKFLOW-3115` —
> `current_state` literals against declared states, narrowed to one workflow's states
> when the file pins exactly one declared `doc_type`. **All four slices are
> implemented**; track F (structured error details) remains gated on the Phase 34
> compatibility contract, carrying this document's target shape.
> The follow-up campaign from
> [workflow expressiveness](workflow-expressiveness.md) (all four slices shipped,
> #534–#537) and the [procurement demo](procurement-demo.md). Grounded in what the
> `dispatch:` diagnosis exposed: the transition pipeline exists three times, the
> selector reads HTTP artifacts in-process, and the route topology was owned by a
> Camel default nobody had chosen.

## What the dispatch diagnosis exposed

Shipping `dispatch:` (#536) required a 30-second-hang diagnosis whose root cause —
Camel 4's rest-dsl `inlineRoutes` default folds every `from(direct:…)` transition
route into its REST route and removes the direct consumer — forced an internal
`direct:<workflow>.<transition>.attempt` shadow route per dispatch member. That fix is
correct and shipped, but the shape of the workaround points at the real defect:

**The documented transition pipeline — security → decide → guard → advance → stamp →
scoped command — is not a thing. It is a Camel route arrangement.** Today it exists
three times:

1. The REST route per transition (`TransactionalCommandProcessor` with a
   `WorkflowBinding`), reachable only over HTTP after rest-dsl inlining.
2. The `.attempt` shadow route per dispatch member — the same processor pipeline
   compiled a second time so something in-process has a consumer to send to.
3. `TestRunner.fireTransition` in `tesseraql-test-core` — a deliberate test-side
   implementation of the pipeline *contract* (documented in [testing](testing.md)),
   which must be updated in lockstep whenever transition semantics change. The
   `stamp:` slice already paid this tax once: engine and test runner each grew the
   same stamp application.

Downstream of that, the dispatch selector inherited HTTP as its calling convention:
it sends a copied exchange to the shadow route and decides fall-through by reading
the response status **and matching `TQL-WORKFLOW-3201`/`3202` as substrings of the
rendered JSON body**. It works — the runtime IT proves it — but an engine deciding
control flow by grepping its own error page is not a contract, and the aggregate
`422` can only report per-member HTTP statuses because the in-process refusal detail
(the guard's declared code) was already flattened into a rendered body.

Three smaller frictions surfaced on the same path:

- The two `propose_*` members in the procurement demo declare **identical
  `decide:` blocks** — the dispatch has no place to evaluate a decision once for all
  members.
- `doc_type` literals are linted (`TQL-WORKFLOW-3114`) but `current_state` literals —
  the same typo class, far more frequent in gallery SQL — are not.
- The error payload's flat `putIfAbsent` details merge forced the guard refusal keys
  to be named `guard`/`guardMessage` because `code`/`message` would be shadowed by
  the renderer's own top-level keys.

This design turns each of those from a workaround into a specification.

## Track A — `TransitionExecutor`: the pipeline becomes an engine object

### What

A single class owning the transactional core of a transition, callable from any
context that holds a JDBC connection:

```
outcome = TransitionExecutor.execute(connection, transition, stores, resolver, context, command)
```

with the step order the docs already promise ([approval workflow](approval-workflow.md)):

1. `ensureInstance` (managed) and **document load** (`document.*` binds);
2. `decide:` evaluation — after document binds, before the guard;
3. state legality — mismatch throws `TQL-WORKFLOW-3201`;
4. guard, both forms — expression or SQL guard file; refusal throws
   `TQL-WORKFLOW-3202` carrying `guard`/`guardMessage`;
5. task authority — an open task the caller does not hold throws `TQL-WORKFLOW-3203`;
6. conditional state advance (managed store or app `stateColumn`) — a raced
   concurrent transition surfaces as `409`;
7. `stamp:` application and in-memory document refresh;
8. the **command callback** — the caller executes its scoped command SQL and returns
   the affected-row count; zero rows throws `TQL-WORKFLOW-3204`.

### Where it lives, and why

`tesseraql-yaml`. The precedent is `DecisionSets`: the yaml module already hosts
model + connection-level evaluation (decision tables evaluate against a
`Connection`), it depends only on `tesseraql-core`, and **both** consumers —
`tesseraql-compiler` (the route processors) and `tesseraql-test-core` (the suite
runner) — already depend on it. The `WorkflowStore`/`WorkflowTaskStore` SPIs it needs
are in `tesseraql-core`; the scope resolver is passed as the same core-level
interface `SqlRenderer` already accepts, so identity stays out of yaml.

The input is a new `CompiledTransition` record (document type/table/key, from/to,
initial, managed flag, parsed guard expression, parsed guard-file nodes +
code/message, stamps, compiled decisions, dialect). The compiler maps
`WorkflowBinding` onto it at route-build time; the test runner builds it from the
`TransitionSpec` it already resolves.

### What deliberately stays outside (this slice)

Assign resolution, task opening, history append, and notify remain in the route
layer: they consume runtime-flavored collaborators (notify channels, reminder
deadlines) and the declarative suites document them as out of scope. The executor's
contract ends at "the state advanced, the stamps applied, the command wrote rows."
If suites ever model task lifecycles, the seam is ready — that is a later decision,
not this one.

### Who delegates

- `TransactionalCommandProcessor.beginWorkflow` + `applyStamps` + the zero-row check
  collapse into one executor call (the processor keeps request binding, scope-aware
  command rendering, tasks/history/notify, response rendering).
- `TestRunner.fireTransition` becomes: build `CompiledTransition`, call the executor
  with the suite's rolled-back connection, assert the outcome. The documented
  "update both implementations" tax in [testing](testing.md) is deleted, not paid.

**Behavior-frozen refactor.** The procurement suite (44+ cases), the workflow runtime
ITs (13), and the gallery apps are the fixture: no assertion changes.

## Track B — dispatch drops HTTP: one transaction, one decide, a full 422

### What the 3112 lint already bought us

`TQL-WORKFLOW-3112` requires every dispatch member to carry **one shared effective
security spec** — "a dispatch is one action, one audience." That rule, shipped for
UX honesty, is exactly what makes engine-level dispatch legal: if all members answer
to the same audience, the **dispatch route itself** can carry that spec through the
standard route governance, and member-level REST enforcement adds nothing for the
dispatch path.

### The new selector

The dispatch route (now a governed route like any synthesized transition, applying
the shared security spec) runs the selection loop in **one transaction**:

1. load the document once;
2. evaluate the **dispatch-level `decide:`** once (new YAML surface, below);
3. for each member in declaration order, call `TransitionExecutor` with the member's
   `CompiledTransition`; a `3201`/`3202` refusal is caught **as a typed exception in
   the same JVM** — record `{transition, code, guard}` and try the next member;
4. the first member that passes advances/stamps/commands — commit; its rendered
   response is the dispatch response, plus a `transition` field naming the member
   that fired (the client may not know the lane, but it may ask);
5. none held ⇒ roll back and answer `422` with
   `attempted: [{transition, status, code, guard?}, …]` — the member's declared
   guard code (`not-funded`, `shipment-not-registered`) finally reaches the caller,
   because it was never flattened into a rendered body.

A refused member writes nothing (the guard runs before the advance), so trying
members inside one transaction is safe; the inter-attempt race window of the
attempt-per-transaction model disappears, and the winner's advance remains the
conditional UPDATE that already turns real races into a member-owned `409`.

### `dispatch:` gains `decide:`

```yaml
dispatch:
  - id: propose
    decide:
      deliveryAutoAccept:
        use: deliveryAutoAccept
        params: { slipDays: document.slip_days }
    oneOf: [propose_accept, propose_review]
```

Evaluated once after the document loads; results are visible to every member's guard
as `decision.*`. Members may still declare their own `decide:` (evaluated per
attempt, as today), but a member alias colliding with a dispatch-level alias is a
lint error (`TQL-WORKFLOW-3112`) — one name, one evaluation. The procurement
`propose` pair's duplicated block collapses into the dispatch.

### What gets deleted

- The `.attempt` shadow routes and their reloader ids (shipped in #536 — they made
  dispatch correct immediately; this is the durable shape).
- The status-plus-body-substring fall-through in `WorkflowDispatchProcessor`.
- The per-attempt `ProducerTemplate` send and exchange copying.

Members stay individually callable over REST, unchanged.

## Track C — the `dispatch:` suite target

With A and B in place, the declarative suites get the selector for the price of a
loop: a `dispatch:` case target mirrors `transition:` but runs the member-selection
loop over the executor inside the same rolled-back transaction.

```yaml
- name: a funded request settles through the dispatch
  principal: { subject: requester-1 }
  dispatch: { workflow: funded_request, id: settle, key: PR-3 }
  expect: { rowCount: 1 }

- name: an already-settled request reports both refusals
  dispatch: { workflow: funded_request, id: settle, key: PR-3 }
  expect:
    rows:
      - { code: TQL-WORKFLOW-3202, attempted: "clear,writeoff" }
```

Refusals surface as code rows in the `transition:` idiom; the none-held case exposes
the attempted list as data. The dispatch — the button the UI actually calls — becomes
testable without HTTP.

## Track D — lint `TQL-WORKFLOW-3115`: `current_state` literals

The same scanner that ships as `TQL-WORKFLOW-3114` (doc_type literals), pointed at
the same typo class one column over: a string literal compared to `current_state` in
SQL that references `tql_workflow_instance` must name a declared workflow state.

- **Narrowing**: when the same SQL file pins exactly one `doc_type` literal that maps
  to one declared workflow, the literal is checked against *that workflow's* states;
  otherwise against the union of all declared states. (The gallery idiom
  `wi.doc_type = 'rfq' and wi.current_state = 'issued'` narrows naturally.)
- **Scanner honesty, kept**: the scan stays regex-based (`=` and first-literal
  `in (`), and stays out of SQL that never mentions the managed table. Moving
  3114/3115 onto the parsed SQL tree is deliberately **not** in scope:
  `Sql2WayParser` models text and bind sites, not comparison expressions, and
  growing it an expression grammar for a lint is the tail wagging the dog. If the
  parser ever grows one for another reason, these lints move onto it.
- The dispatch enum-disjointness prover (member guards over one enum-typed
  decision output or stamped column, reusing `TQL-DECISION-4712`'s machinery)
  remains the separate designed follow-up noted in
  [workflow expressiveness](workflow-expressiveness.md); it is not folded in here.

## Track E — topology is a choice, not a default

The 30-second hang existed because the framework's route topology — which routes
exist, which consumers exist — was silently owned by a Camel default
(`inlineRoutes=true` since 4.0). Pin it explicitly at both
`restConfiguration()` sites (`RouteCompiler`, `RouteReloader`) with the reason in a
comment: a future Camel upgrade that flips the default must not silently double the
route count and resurrect direct consumers nobody sends to (or the reverse). One
line, twice; also an input to the Camel 4.21 adoption review.

## Track F — structured error details (target shape; gated on the compat contract)

Today `TqlException.details()` merges **flat** into the rendered error object with
`putIfAbsent`, so the renderer's own top-level `code`/`message` shadow any
same-named detail key — which is why the guard refusal keys are spelled
`guard`/`guardMessage` instead of the natural `code`/`message`. Every future
feature detail dodges the same landmine.

Target shape — details become a namespace:

```json
{ "error": { "code": "TQL-WORKFLOW-3202", "message": "…", "details": { "code": "not-funded", "message": "…", "attempted": [] } } }
```

This is a **breaking payload change** for every consumer (Studio, portal try-it,
hypermedia-components field-errors, gallery clients, the suites' code-row idiom), so
it belongs to the roadmap Phase 34 compatibility-contract work and must land before
1.0 — this document fixes the target shape so Phase 34 inherits a decision, not a
debate. Out of scope here: the migration mechanics (dual-emit window vs. one-shot
pre-1.0 break).

## Slices

1. **`TransitionExecutor`** — extract into `tesseraql-yaml`; `TransactionalCommandProcessor`
   and `TestRunner.fireTransition` delegate; behavior-frozen against the existing
   suites/ITs/gallery. The `inlineRoutes` pin (Track E) rides along as the one-line
   hardening it is.
2. **Engine-level dispatch** — governed dispatch route, one-transaction selection
   loop, dispatch-level `decide:` (+ alias-collision lint), `attempted[].code` in the
   none-held `422`, `transition` in the success payload; delete the `.attempt` shadow
   routes and their reloader ids; convert the procurement `propose` pair's duplicated
   decide.
3. **`dispatch:` suite target** — the selection loop in the rolled-back suite
   transaction; procurement gains dispatch cases.
4. **Lint `TQL-WORKFLOW-3115`** — `current_state` literals, with single-doc_type
   narrowing; gallery proves zero false positives.

Track F ships with Phase 34, carrying this document's target shape.

## Open questions

- Should the dispatch success payload's `transition` field be opt-out (a client that
  must not learn the lane)? Default-on seems right — the audit trail names the fired
  transition anyway.
- A member's own `decide:` re-using the dispatch-level table with different params:
  legal (different alias) or suspicious enough to warn?
- When the executor exists, should the deadline sweeper's escalation path also run
  through it (today it advances state through its own store calls)? Likely yes, as a
  later slice — same drift argument as the test runner.
