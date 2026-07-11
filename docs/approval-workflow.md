# Approval workflow

An approval workflow drives a **business document** — a purchase request, an expense claim, a leave
application — through a sequence of states by way of human decisions: submit, approve, reject, return
for rework. TesseraQL has the pieces a workflow is made of (transactional writes, row scoping, an
expression language, a cluster-safe scheduler, an outbox); the approval workflow composes them into a
declared state machine rather than adding a new runtime subsystem. The worked example in the gallery
is a purchase-request approval application built only with YAML, 2-way SQL, and templates.

This is **not** route governance. The route-governance approval gate
([`RouteGovernance`](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/governance/RouteGovernance.java))
reviews *routes* at build time — a deploy-time control over the application's own surface. Approval
workflow reviews *documents* at run time — a business control over the data an application moves.
The two share neither namespace nor lint family.

It builds on subsystems already in the framework:

- **[Transactional writes](transactional-writes.md)** — a transition is one business operation: an
  ordered list of 2-way SQL steps in a single transaction, with row-count expectations turning a
  stale transition into a `409 Conflict`, and canonical `/* audit.user */` / `/* audit.now */` binds.
- **[Organizational data scoping](data-scoping.md)** — the same `/*%scope … */` directive that
  confines what a caller can *see* confines what a caller can *transition*; the org-unit foundation
  (`OrgUnitStore`) that scope resolution reads is the substrate workflow assignee resolution reads.
- **The core expression language** (`io.tesseraql.core.expr`) — whitelist-only, side-effect-free, no
  method calls — evaluates transition guards.
- **The scheduler** (quartz/timer routes with cluster-safe `tql_job_claim` claiming) — fires
  deadlines, escalations, and reminders exactly once across a cluster.
- **The outbox** ([notifications](notifications.md), [messaging](messaging.md)) — assignment and
  escalation notifications ride the existing at-least-once delivery, visible in the operations
  console.
- **IAM's managed/SQL realm duality** (`RealmConfig`) — workflow state lives in managed
  `tql_workflow_*` tables or in app-owned tables behind SQL contracts, selected by one config key.

## Three invariants

Three invariants hold throughout:

1. **SQL-first, no hidden state.** Process state is a column on a row (a managed `tql_workflow_*`
   table or an app-owned table); every transition is plain 2-way SQL that runs in a SQL tool. The
   engine never mutates state behind the author's back.
2. **Deny-by-default.** A transition not declared for the current state is rejected. A caller with no
   row authority over the document (the transition's `/*%scope … */`) transitions nothing. A guard
   that does not evaluate truthy blocks the transition.
3. **Machine-checkable.** Workflows carry their own lint family (`TQL-WORKFLOW-31xx`) and a
   `workflow` coverage kind.

## The model

A **workflow** is a `kind: workflow` document under `workflow/`, indexed into the manifest like
`scope/` and `consume/` documents. It declares:

- the **document** it governs — an app-owned business table, its key, and the column (managed mode:
  the row in `tql_workflow_instance`) that holds the current state;
- an ordered set of **states**, one marked `initial`, zero or more marked `terminal`;
- a set of **transitions**, each from one state to another, carrying a **guard** (state-machine
  legality), a **command** (the transactional, scoped write), and an optional **assignee
  resolution** (who receives the resulting task);
- optional **deadlines** per state, with escalation and reassignment driven by the scheduler.

```yaml
# workflow/purchase_request.yml
version: tesseraql/v1
id: purchase_request
kind: workflow
document:
  type: purchase_request
  table: purchase_requests          # the app-owned business table
  key: id
  stateColumn: wf_state             # app mode: a column on the business table
                                    # managed mode: omit — state lives in tql_workflow_instance
initial: draft
states:
  - { id: draft,     type: initial }
  - { id: submitted }
  - { id: approved,  type: terminal }
  - { id: rejected,  type: terminal }
transitions:
  - id: submit
    from: draft
    to: submitted
    guard: "document.amount > 0"                  # state-machine legality (expression language)
    command: submit.sql                           # transactional-write step; /*%scope%/ confines the write
    assign:                                       # who gets the resulting task (the dual of a scope)
      file: assignees/manager_of_requester.sql
      params:
        requester: document.created_by
  - id: approve
    from: submitted
    to: approved
    guard: "principal.role == 'approver'"
    command: approve.sql
  - id: reject
    from: submitted
    to: rejected
    command: reject.sql
deadlines:
  - state: submitted
    within: 48h
    onBreach:
      escalate: approve                           # auto-run a transition…
      reassign: assignees/dept_head.sql           # …or reassign the task to a fallback resolver
```

Each `*.sql` stays runnable in a plain SQL tool — `submit.sql` is an ordinary `UPDATE` with a 2-way
scope directive and audit binds, `assignees/manager_of_requester.sql` an ordinary `SELECT`.

### Guard versus scope: legality versus authority

Two orthogonal checks gate every transition, and they answer different questions:

- The **guard** (`io.tesseraql.core.expr`) answers *is this transition legal right now?* — a
  whitelist-only boolean over `document.*`, `task.*`, and `principal.*` paths (e.g.
  `document.amount > 0`, `principal.role == 'approver'`). It is the same evaluator the `validate:`
  rules ([declarative validation](declarative-validation.md)) and `/*%if … */` directives use:
  comparison and logical operators, dotted paths, no function calls, no side effects.
- The **scope** ([data scoping](data-scoping.md)) answers *does this caller have authority over this
  row?* — a `/*%scope … */` directive in the transition's `UPDATE … WHERE`, resolved against the
  principal, parameterized, deny-by-default. The write touches only rows the caller is authorized
  over.

Keeping them separate is deliberate: the guard expresses the process, the scope expresses
organizational reach. A transition with a satisfied guard but no authorized rows updates nothing and
returns a `409`/`403`, exactly as a scoped write does today. (Why guards do not use the policy
matcher and scopes do: the matcher answers role/permission/claim membership; the guard answers a data
predicate — the same split data-scoping.md draws between `when:` arms and the expression language.)

### Assignee resolution is the dual of a scope

A **scope** maps `principal → predicate over rows`. **Assignee resolution** maps `document → set of
principals` — the same org graph, the opposite direction. So it reuses the same foundation. A
transition's `assign` contract is a 2-way SQL `SELECT` returning `assignee`/`candidate_group` rows:

```sql
-- workflow/assignees/manager_of_requester.sql
-- the principals who manage the requester's org unit (and everything above it)
select u.subject
from   tql_users u
join   tql_user_roles ur on ur.user_id = u.id and ur.role = 'approver'
where  u.org_unit in (
         select ancestor_id from tql_org_closure
         where descendant_id = /* requesterUnit */ 'U1')
```

The `tql_org_closure` join is the managed org-unit foundation ([data scoping](data-scoping.md))
consumed unchanged (`OrgUnitStore.descendants(...)` is the Java seam when resolution is done in code
rather than SQL). In `app` mode the same contract is written against the application's own
organization tables.

## A transition is a transactional, scoped write

A transition route compiles to a recipe that, in one transaction:

1. reads the document's current state and checks the transition is declared for it (else
   `TQL-WORKFLOW-3201`, mapped to `409`);
2. evaluates the **guard**; a falsy guard blocks (`422`);
3. runs the transition's **command** — a transactional step list
   ([transactional writes](transactional-writes.md)), its `UPDATE` carrying the `/*%scope … */`
   write authority and `/* audit.user */` / `/* audit.now */` binds, with
   `expect: { rows: 1, onMismatch: conflict }` turning a concurrent transition into a `409`;
4. advances the state column (app mode) or the `tql_workflow_instance` row (managed mode);
5. resolves **assignees** and writes the resulting task(s), completing the prior state's open tasks
   in the same transaction;
6. appends an immutable **history** row (see [Audit trail](#audit-trail));
7. optionally enqueues an **outbox** notification ([notifications](notifications.md)) for the new
   assignee.

All seven happen in the command's transaction, so a transition is atomic and replays safely through
the existing idempotency machinery — the same guarantees `command-json` already gives a write.

Assignment is itself authority-bearing: a document with open tasks may only be transitioned by a
caller who holds one — the direct assignee or a member of a candidate group — else
`TQL-WORKFLOW-3203` (403). This is framework-enforced rather than left to an app-authored scope; it
is the dual of a scope over the task table, which an app may still author for the inbox query.

### Transitions are compiler-synthesized routes

Authors do not write a route per transition. The `workflow/` document declares the states and
transitions, and the compiler **synthesizes** one transactional-command route per transition — the
way `consume/` documents ([messaging](messaging.md)) synthesize `queue-consume` routes. Each
transition compiles to a `direct:workflow.<id>.<transitionId>` route and a
`POST {http.basePath}/{key}/{transitionId}` endpoint, carrying the transition's (or the workflow's)
`security`.

The synthesized route runs the existing command machinery with one added workflow binding (document
type/table/key, `stateColumn`, `from`/`to`, the parsed guard, the transition id). In a single
transaction the processor does:

```text
open connection / begin tx
  ├─ store.ensureInstance(cx, docType, docId, initial, tenant)   # managed only; app no-op
  ├─ document ← SELECT * FROM <table> WHERE <key> = ?            # bound into context as `document`
  ├─ current  ← store.currentState(cx, …)                        # managed: instance row / app: document.<stateColumn>
  ├─ if current != from        → TQL-WORKFLOW-3201 (409)         # legality, in-tx, no TOCTOU
  ├─ if !guard.evalBoolean(ctx) → TQL-WORKFLOW-3202 (422)        # guard over document.* / principal.*
  ├─ validate: rules (existing declarative-validation step)
  ├─ store.advanceState(cx, docType, docId, from, to)            # conditional UPDATE; 0 rows → 409
  ├─ author command step(s)                                      # existing step loop: scoped write + audit + expect
  ├─ store.appendHistory(cx, …)                                  # append-only, same tx
  └─ commit
```

The only change to request binding is that the transition adds a `document` key (the loaded row)
to the execution context the guard and the command SQL already read (`principal`, `body`, `path`,
`tenant`).

## Managed and app modes

Consistent with IAM's managed/SQL realm duality and the org-unit model
([data scoping](data-scoping.md)), workflow state has two modes, selected by
`tesseraql.workflow.mode` (default `app`):

- **`managed`** — the runtime provisions three managed tables and maintains them through a
  `WorkflowStore`:
  - `tql_workflow_instance` — one row per document under workflow (document type, document id,
    current state, tenant);
  - `tql_workflow_task` — open and completed tasks (instance, assignee, candidate group, due-at,
    delegated-to, status);
  - `tql_workflow_history` — the append-only audit trail (instance, transition, from/to state, actor,
    timestamp, note).

  The DDL ships per dialect (`postgres`/`mysql`/`oracle`/`sqlserver`) and is applied by
  `SqlScripts.applyForVendor`, exactly as `tql_org_unit`/`tql_org_closure` are. To stay portable it
  uses inline `unique` constraints rather than a separate `CREATE INDEX` (MySQL does not accept
  `CREATE INDEX IF NOT EXISTS`); the Oracle variant uses plain `CREATE` plus ORA-00955 tolerance and
  the SQL Server variant guards with `if object_id(...) is null`, exactly as the org-unit DDL does.
  The store's `advanceState` is a conditional
  `UPDATE tql_workflow_instance SET current_state = ? … WHERE … AND current_state = ?`; zero rows
  affected means a concurrent transition and surfaces as a `409`. The store is bound in
  `TesseraqlRuntime` at the same point the `OrgUnitStore` is, only when the app declares workflows
  and the mode is `managed`.

- **`app`** (default) — the application owns its workflow tables (or folds state into the business
  table via `stateColumn`); transitions, tasks, and history are app-provided SQL contracts resolved
  the way IAM resolves a SQL-realm contract from its `sqlRoot`. Nothing managed is provisioned:
  state is kept via `UPDATE <table> SET <stateColumn> = ? WHERE <key> = ? AND <stateColumn> = ?`
  (the same zero-rows-means-`409` conflict check), and history is an optional app contract.

The `WorkflowStore` SPI lives in `tesseraql-core` (dependency-free); the `JdbcWorkflowStore` and DDL
live in `tesseraql-operations` (heavy dependencies behind the SPI). Sketch, mirroring `OutboxStore`'s
connection-threaded inserts so a transition stays atomic:

```java
public interface WorkflowStore {
    String currentState(Connection cx, String docType, String docId);
    void advanceState(Connection cx, String docType, String docId, String from, String to);
    String openTask(Connection cx, WorkflowTask task);          // returns task id
    void completeTask(Connection cx, String taskId, String actor);
    void reassign(Connection cx, String taskId, String assignee);
    void appendHistory(Connection cx, WorkflowHistory entry);   // append-only
    List<WorkflowTask> overdue(Instant asOf, int limit);        // scheduler sweep
}
```

## Workflow document schema

The full document surface, including the optional `mode`, `http`, and `security` keys:

```yaml
# workflow/purchase_request.yml
version: tesseraql/v1
id: purchase_request
kind: workflow
mode: managed                 # omitted ⇒ tesseraql.workflow.mode (default app)
document:
  type: purchase_request      # managed: tql_workflow_instance.doc_type
  table: purchase_requests    # required in BOTH modes (the guard loads the document row)
  key: id
  stateColumn: wf_state       # app mode only (managed keeps state in the instance row)
http: { basePath: /purchase-requests }
security: { auth: browser, policy: pr-actor }   # default for every transition; per-transition override allowed
initial: draft
states:
  - { id: draft,     type: initial }
  - { id: submitted }
  - { id: approved,  type: terminal }
  - { id: rejected,  type: terminal }
transitions:
  - { id: submit,  from: draft,     to: submitted, guard: "document.amount > 0",        command: submit.sql,
      assign: { file: assignees/manager.sql, params: { requester: document.created_by } } }
  - { id: approve, from: submitted, to: approved,  guard: "principal.role == 'approver'", command: approve.sql }
  - { id: reject,  from: submitted, to: rejected,                                          command: reject.sql }
deadlines:
  - { state: submitted, within: 48h, onBreach: { escalate: approve } }
```

`mode` overrides `tesseraql.workflow.mode` per document. `document.table` is required in both modes
because the guard loads the document row; `stateColumn` applies in `app` mode only. The workflow's
`security` is the default for every synthesized transition endpoint; a transition may override it.

## Task inbox

"The tasks I can act on" is a scope over the task table — direct assignee **or** a candidate group I
belong to **or** a task delegated to me — which is exactly the additive (OR) composition
[data scoping](data-scoping.md) already gives:

```yaml
# workflow/inbox_scope.yml
id: workflow_inbox
kind: scope
match:
  - when: { permission: workflow:act }
    file: my_open_tasks.sql
    params:
      uid: principal.subject
      groups: principal.groups
```

```sql
-- workflow/my_open_tasks.sql  (one arm; additive OR if more roles apply)
$.status = 'OPEN' and (
     $.assignee     = /* uid */ 'u'
  or $.candidate_group in /* groups */ ('g')
  or $.delegated_to  = /* uid */ 'u')
```

The task store (`WorkflowTaskStore`/`JdbcWorkflowTaskStore`) is provisioned whenever any transition
assigns, independent of the state mode, so one inbox spans every workflow.

The inbox page is an ordinary `query-html` route whose SELECT applies `/*%scope workflow_inbox … */`,
rendering an `hc-table` fragment through the existing Thymeleaf/`hc-shell` pipeline (any gap belongs
upstream in Hypermedia Components — no app CSS over `hc-*`). The fragment refreshes in place with
htmx (`hx-trigger="load, every 30s"`), and the action buttons POST to the transition routes. Because
the *transition* carries its own `/*%scope … */` write authority, a task appearing in an inbox is
necessary but not sufficient — the write still confirms authority server-side.

## Deadlines, escalation, and delegation

A workflow declares deadlines per state; the opened task carries a `due_at` set from the `to` state's
`within` (e.g. `within: 48h`). A managed **sweeper** — a fixed-delay timer route claimed cluster-safe
through `tql_job_claim` (`JobRepository.tryClaimFiring`, the same machinery scheduled jobs use, at the
`tesseraql.workflow.sweep.interval`, default 60s) — calls `WorkflowTaskStore.overdue(...)` and applies
each breached state's `onBreach`, **exactly once** even across nodes:

- **`reassign`** — reassigns the overdue task to the fallback resolver named by the SQL contract (a
  2-way SQL `SELECT` returning the new assignee), clearing the task's `due_at` and recording an
  `escalate` history row (actor `system`); or
- **`escalate`** — auto-fires the named transition **as the system**: it advances the document from
  the deadline's state, runs the transition's command (with `/* key */` and `/* audit.* */` binds, so
  `audit.user` is `system`), completes the open tasks (so it cannot re-fire), and records a history
  row under the transition id. The lint (`TQL-WORKFLOW-3107`) checks the named transition starts from
  the deadline's state. `escalate` takes precedence when both are declared.

```yaml
deadlines:
  - state: submitted
    within: 48h
    onBreach: { reassign: dept_head.sql }   # reassign an overdue task to this resolver…
  - state: review
    within: 72h
    onBreach: { escalate: auto_approve }    # …or auto-fire this transition as the system
```

**Delegation** is a built-in operation, not a transition (it changes no state): the current assignee
reassigns the document's open task to another principal at `POST {basePath}/{key}/delegate/{to}`, who
then sees it in their inbox (the task store reassigns the open tasks to `{to}`). Only a caller who
holds the task may delegate, else `TQL-WORKFLOW-3203` (403).

**Reminder notifications** ride the [notification channels](notifications.md): a workflow declares a
`notify:` block whose `assigned` reminder fires when a transition opens a task and whose `escalated`
reminder fires when the sweeper reassigns one. Each is a `NotifySpec` (channel, optional `when`
guard, `payload`) enqueued as a `NOTIFICATION` outbox event **in the same transaction** as the task
change — so a rolled-back transition never notifies and a committed one notifies at-least-once, with
the same retries and dead-letters as a route's `notify:`. The resolved `assignee` is in the payload
scope:

```yaml
notify:
  assigned:  { channel: task-mail, payload: { to: assignee, doc: document.id } }
  escalated: { channel: task-mail, payload: { to: assignee, doc: docId } }
```

## Audit trail

Every transition appends one immutable row to the history (`tql_workflow_history` in managed mode, an
app contract otherwise) **inside the transition's transaction** — so the audit record and the state
change commit or roll back together; there is no window where a state advanced without a recorded
reason. A row carries the instance, the transition id, the from/to states, the actor
(`/* audit.user */`, or the workflow itself for an escalation), the timestamp (`/* audit.now */`,
one clock reading per command), and an optional note. History is append-only: the SPI exposes no
update or delete. Downstream consumers (a "request approved" mail, an event for a neighbouring
system) ride the outbox ([notifications](notifications.md), [messaging](messaging.md)), dispatched
after commit.

## Governance and testing

A lint family catches a malformed or unreachable state machine before it ships:

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-WORKFLOW-3101` | error | a transition's `from`/`to` (or the workflow's `initial`) names a state not declared in `states` |
| `TQL-WORKFLOW-3102` | error | no `initial` state, more than one `initial` state, or a state unreachable from `initial` |
| `TQL-WORKFLOW-3103` | error | a transition's `guard` is not a valid whitelist expression, or references a path outside `document.*`/`task.*`/`principal.*` |
| `TQL-WORKFLOW-3104` | error | a transition's `command` or an `assign`/`onBreach` `file` is missing under `workflow/` |
| `TQL-WORKFLOW-3105` | warning | a non-terminal state has no outgoing transition (a dead end), or a terminal state has one |
| `TQL-WORKFLOW-3106` | error | mode mismatch: `app` needs `document.{table,key,stateColumn}`, `managed` needs `document.{type,table,key}` |
| `TQL-WORKFLOW-3107` | error | a deadline's `escalate` names a transition that does not start from the deadline's state |
| `TQL-WORKFLOW-3110` | error | `tesseraql.workflow.mode` is set to something other than `managed` or `app` |

The runtime fails closed at execution, under its own `WORKFLOW` error domain (so its codes match the
lint family rather than borrowing `TQL-SQL-*`): a transition not declared for the current state is
`TQL-WORKFLOW-3201` (`409`); a transition whose guard is falsy is `TQL-WORKFLOW-3202` (`422`); a
transition attempted by a caller who holds none of the document's open tasks is `TQL-WORKFLOW-3203`
(`403`); a transition whose scoped write matches no authorized row is a `409`/`403`, exactly as a
scoped write is today — a workflow can never silently no-op a transition.

The **`workflow`** coverage kind declares one item per **transition** across `workflow/` documents; a
transition counts as covered when a declarative suite exercises a route that runs it (the same
SQL-file basis as route and `data-scope` coverage). An app with no workflows reports a 1.0 ratio.
Gate it with `coverage.thresholds.workflow`. Per-state-path coverage — every state's outgoing
transitions exercised — is not currently supported.

## Relationship to data scoping

[Data scoping](data-scoping.md) deliberately factored the org-unit hierarchy as a **shared
foundation** and reserved the seams the approval workflow plugs into, so the workflow introduces no
second org model:

- **Assignee resolution** is the dual of a **scope** — same `tql_org_closure` graph,
  `OrgUnitStore.descendants(...)` the shared Java seam.
- A **task inbox** is a scope over the task table; additive (OR) composition is exactly its
  semantics.
- A **transition** is a scoped write — the `/*%scope … */` in its `UPDATE … WHERE` confines it to
  authorized documents, complementing the guard.

## Module placement

| Concern | Module |
| --- | --- |
| `WorkflowDefinition` model, `WorkflowStore` SPI, guard evaluation | `tesseraql-core` (dependency-free) |
| `JdbcWorkflowStore`, managed DDL | `tesseraql-operations` |
| Transition recipe compilation, inbox templates | `tesseraql-compiler` |
| `kind: workflow` parsing, `TQL-WORKFLOW-31xx` lint | `tesseraql-yaml` |
| Mode wiring, the sweeper route | `tesseraql-camel-runtime` |

## Design notes

**Why a native state machine, not an embedded engine.** Embedding an external workflow engine
(Flowable, Camunda, jBPM) was weighed and rejected. An external engine would keep process state in
the engine's own schema, mutated by the engine's runtime rather than by plain-SQL-tool-runnable 2-way
SQL; it is a heavy runtime dependency with its own persistence, threading, and transaction model to
reconcile with Camel and the TesseraQL outbox; and it would duplicate machinery the framework already
owns — a transaction-scoped write engine, row scoping, an expression language, and a cluster-safe
scheduler. The native state machine reuses all of that and adds no runtime dependency. The seam
survives the decision: the `WorkflowStore` SPI and the `kind: workflow` document are where an
external engine *could* later plug in for an application that needs BPMN, without changing the YAML
surface — the same way the PDF codec keeps its engine SPI
([printable documents](printable-documents.md)).

**One transaction engine, not two.** A transition extends the transactional-writes command processor
rather than running through a parallel one, so there is one transaction engine, the state advance and
history write ride the command's own JDBC `Connection` (the connection-threaded idiom
`OutboxStore.insert(Connection, …)` already uses), and the guard runs *inside* the transaction with
no time-of-check/time-of-use gap.
