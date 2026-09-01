# The workflow surface — transitions, the stepper, and the task queue

> **Status: slice 1 (the region and the stepper) shipped 2026-09-01.** Recorded
> deviations from the text below: the gallery dogfood is the **purchase-request** detail
> view, not helpdesk — both workflow gallery apps are deliberately bearer-only API
> shapes, and converting helpdesk's posture was a bigger decision than a dogfood
> warrants; purchase-request also exercises the managed-mode instance read the inline IT
> app's second workflow covers. The browser flow (PRG + CSRF) is proven by
> `WorkflowSurfaceIntegrationTest` on browser-auth'd workflows. The PRG fallback is
> `_return`-else-root with no referer leg — the region always renders `_return`, so the
> referer leg was dead weight. The missing key/state-column check is a render-time
> refusal (`TQL-VIEW-3328`), not a static lint: `select *` makes a static check a liar.
> Open question 1 resolved accordingly: gallery workflows stay bearer, and a bearer
> workflow's region still renders — its actions are the API's. Slices 2-3 not started.
>
> **Original design below.** The workflow-actions campaign from
> [hc-recipe-alignment.md](hc-recipe-alignment.md): the `workflow:` engine ships
> transitions, guards, per-transition security, and correct 409 semantics, and no surface
> renders any of it. This design gives the engine its face — a transitions region and
> lifecycle stepper on detail views, comment-required transitions, and the task queue
> page — in three slices. Measured against main at #1104; the upstream contract is
> `recipes/workflow-actions/contract.md` in the hypermedia-components repository.

The contract's core rule: **the server renders only the legal transitions for this user
on this state.** No client-side state machine, no role check in JS. What the user can
never do (wrong role) is absent; what they could do but not now (failed precondition)
renders disabled with the reason. The action set IS the state, and the stepper is the
same truth in picture form.

## What exists

Everything below the markup. `kind: workflow` declares states, transitions (`from`/`to`,
`guard`, `stamp`, `join`, `bulk`, per-transition `security:`), and the document binding
(`table`, `key`, `stateColumn` in app mode; `tql_workflow_instance` in managed mode).
`RouteCompiler.buildWorkflow` synthesizes `POST {basePath}/{key}/{transition}` routes;
`TransitionExecutor` enforces state-as-lock (the conditional `advanceState` UPDATE — a
stale or illegal transition is `TQL-WORKFLOW-3201`, 409), guards (`3202`, 422), and task
authority (`3203`, 403). `tql_workflow_history` records every move and already carries a
`note` column, written as null today.

What is missing, precisely (the survey behind this design):

- Nothing maps a view to a workflow. The detail view knows its route and row, not that
  `tickets.status` is the `ticket` workflow's state column.
- `ViewSpec` hard-refuses `actions:` off list views; detail views render fields and
  children only. `ViewBinding.detailModel` already receives the `permits` predicate
  (the render-time policy check the form surface uses for field visibility) and ignores
  it — the seam is open.
- A synthesized transition route rejects every posted body field (`input:` is empty and
  the mass-assignment guard rejects unknowns), responds `200 {"ok": true}` JSON only,
  and has no redirect leg — a no-JS form post lands the browser on a JSON blob.
- `WorkflowTaskStore` has no by-assignee query, and `tql_workflow_task` has no index for
  one. A task row names the state it is open in, not the transition owed.

## Decision 1 — the view names the workflow

A detail view opts in with one key:

```yaml
kind: view
recipe: detail
workflow: ticket
```

`workflow:` is a new `ViewSpec` record component, legal only on `recipe: detail`
(`TQL-VIEW-33xx` otherwise; the vocabulary is strict and reflective, so it must be a real
component). The compiler resolves the named workflow from `AppManifest.workflows()`,
fails the build on an unknown id, and binds the region to the workflow's document
declaration. The route's row must carry the key column and, in app mode, the state
column — a detail SQL that omits either is a lint error, because the region cannot
render truthfully without them. In managed mode the current state is read from
`tql_workflow_instance` at render.

Contract-first holds: a detail view without `workflow:` renders exactly what it renders
today. No inference from table names — an explicit declaration or nothing.

## Decision 2 — the transitions region renders three-valued legality

For each declared transition, evaluated against the loaded row and the request
principal, in this order:

1. **Policy** — the transition's `security.policy` (falling back to the workflow
   default) through the existing `permits` predicate. Not permitted → **not rendered**.
   The wrong role teaches nothing; this is the contract's "can never do" case.
2. **State** — `from` must equal the current state. Other states' transitions are
   **not rendered**; the stepper communicates position, and rendering every edge of a
   free graph disabled would bury the two buttons that matter.
3. **Guard** — an *expression* guard evaluates read-only against `document.*` and
   `principal.*` (the compiled `Expr` is in-memory and side-effect-free). False →
   rendered **`aria-disabled="true"` with the guard's declared `message:`** (or a
   default). A *SQL-file* guard or one reading `decision.*` is **indeterminate at
   render**: it renders enabled, and the executor's `3202` refusal is the truth — the
   422 re-renders the region with the reason. Never pre-run decision tables at render.
4. **Task authority** — when the document has open tasks and `canAct` says this
   principal holds none, the whole action bar renders disabled with the assignment
   shown. `canAct` is document-scoped, which matches: authority gates the bar, not one
   button.

The region is the list surface's action-bar shape: one `<form method="post">` with the
`_csrf` hidden input, each transition a `type="submit"` button carrying
`th:formaction` = the synthesized route (`interpolateAction` resolves the key), and
`data-hc-confirm` on transitions into a terminal state. `data-hc-workflow` marks the
region per the upstream contract.

**The optimistic lock is the state, not a version field.** The upstream contract's
hidden `version` exists so a stale page cannot apply an action the user never saw. Our
engine already guarantees exactly that: the transition id implies its `from` state, and
`advanceState`'s conditional UPDATE refuses when the row moved (`3201`, 409, with
expected/actual in the details). A hidden version would re-implement the same lock one
level up; the deviation is recorded, not papered over.

## Decision 3 — posting is Post/Redirect/Get first, htmx second

The synthesized transition route gains a browser leg: a form-encoded post answers
**303 back to the detail page** (the `_return` discipline the list surface established;
fallback the referer, then the detail path). The JSON contract is untouched — an API
caller still gets `200 {"ok": true}`. On 409/422 the framework's error envelope already
renders as a page; the detail page re-renders current truth on the redirected GET, which
is the contract's "409 re-renders from current truth" satisfied at page granularity.

Recorded deviation from the upstream recipe: no region-granular htmx swap in slice 1.
The contract's fragment choreography (region `outerHTML`, comment field rendered only on
the 422) assumes the route can render the region; ours renders pages. The PRG shape is
the no-JS baseline the recipe itself requires, works identically with htmx boosting, and
keeps the route compiler out of the view-rendering business. If region swaps prove
needed, they layer on later without changing the declaration.

CSRF: the gallery workflows declare `auth: bearer`; a browser-posting region needs
browser auth on those routes. The design takes the dual declaration the account app
uses — the workflow (or the transition) declares browser-compatible security, and
`csrfEnforced` then installs the CSRF step automatically. The helpdesk app converts as
the dogfood; a workflow that stays bearer-only simply renders no region (policy check
fails without a browser principal), which is honest.

## Decision 4 — the stepper is declaration order

`hc-stepper` renders the workflow's states in declaration order — the only order the
model has, and the one the author already writes as the rough lifecycle. The current
state carries `aria-current="step"`; states on the walked path (from
`tql_workflow_history`, or simply positional before the current state in slice 1) carry
`data-state="complete"`; terminal siblings (`approved` / `rejected`) both render, and
the one actually reached is marked. A read-only viewer (policy fails every transition)
gets the stepper without the form — the contract's own rule.

A `join:` transition renders its progress from the stamp columns on the loaded row
("2 of 3 stamped" = the non-null count among `JoinSpec.stamps`), when the detail SQL
selects them.

## Decision 5 — comment-required is a transition declaration

```yaml
- id: reject
  from: submitted
  to: rejected
  comment: required
  command: { file: reject.sql }
```

`comment:` (values: `required`; absent = none) is a new `TransitionSpec` component. The
compiler synthesizes an `input:` entry for `comment` on that transition's route (today's
routes reject every body field — this is the one field that opens), and the executor
writes it to `tql_workflow_history.note` — the column has waited for this. Refusal shape
per the upstream contract: posting without a comment answers 422 with the field-errors
envelope, and the detail page renders the comment field **only for the refused
transition** on the redirected re-render. Every transition accepts an *optional* comment
once the input exists; `required` only changes the refusal.

## Decision 6 — the task queue is the list surface

A "my tasks" page is not a new surface — it is `recipe: list` with
`strategy: snapshot` over `tql_workflow_task`, exactly the pairing the upstream contract
names (the queue side of workflow-actions is the snapshot pager). The framework's part:

- `WorkflowTaskStore.listOpenTasks(cx, subject, groups, limit)` — the missing
  by-assignee query, returning task rows joined with doc type/id/state/due.
- An index migration for `(assignee, status)` and `(candidate_group, status)` on
  `tql_workflow_task` — the outbox V11 recipe: plain `create index` under the tolerated
  duplicate-index errors, three dialect variants. The DDL's "no index keeps it portable"
  comment predates that recipe and is superseded by it.
- A queue row links to the document's detail page, where the transitions region is the
  acting surface — the queue lists, the detail acts, and the two share the transition
  routes (the upstream note about the bulk/single split).

What a task row cannot say is which transition is owed (it records the state, not the
verb); the queue page says "REQ-1003 is in submitted, assigned to you" and the detail
page's legal set says the rest. Recorded as a limitation, not worked around.

## Slices

1. **The region and the stepper**: `workflow:` on detail views, three-valued legality
   (policy absent / state absent / expression-guard disabled-with-reason), `canAct`
   bar gating, the PRG browser leg + CSRF on browser-auth'd workflows, hc-stepper with
   join stamp progress, helpdesk conversion as dogfood, lint for the missing
   key/state column.
2. **Comment-required**: `comment:` on `TransitionSpec`, the synthesized input, the
   history `note` write, the 422 shape, gallery specimen (purchase-request `reject`).
3. **The task queue**: `listOpenTasks` + the index migration + a bundled task list page
   riding `strategy: snapshot`, linking into the detail surface.

## Open questions

- Whether the helpdesk conversion keeps bearer auth for API callers alongside the
  browser leg (dual auth on one route) or splits the surface — slice 1 decides against
  the code, not here.
- Whether "walked path" stepper marking (history-based) is worth the extra read in
  slice 1, or positional marking suffices until someone notices.
- Whether the queue page ships as a bundled framework page (`/_tesseraql/tasks`) or a
  scaffolder emission into the app — leaning bundled, since tasks are cross-workflow
  and the store is framework-owned.
