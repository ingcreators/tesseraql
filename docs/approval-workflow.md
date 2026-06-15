# Approval workflow

> **Status: in progress — slices 1–2 delivered (roadmap Phase 28, Horizon 4).** The workflow core
> (slice 1) and assignee resolution + task inbox (slice 2) ship; deadlines, escalation, and
> delegation (slice 3) remain design. The further-out parts are direction; revisit at each minor
> release. It resolves roadmap decision point 2 in favour of a native SQL-contract state machine
> (see [Decision](#decision-native-sql-contract-state-machine)).

An approval workflow drives a **business document** — a purchase request, an expense claim, a leave
application — through a sequence of states by way of human decisions: submit, approve, reject, return
for rework. TesseraQL has the pieces a workflow is made of (transactional writes, row scoping, an
expression language, a cluster-safe scheduler, an outbox); this phase composes them into a declared
state machine rather than adding a new runtime subsystem.

This is **not** route governance. The route-governance approval gate
([`RouteGovernance`](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/governance/RouteGovernance.java))
reviews *routes* at build time — a deploy-time control over the application's own surface. Approval
workflow reviews *documents* at run time — a business control over the data an application moves.
The two share neither namespace nor lint family.

It builds on subsystems already in the framework:

- **Transactional writes** (Phase 18) — a transition is one business operation: an ordered list of
  2-way SQL steps in a single transaction, with row-count expectations turning a stale transition
  into a `409 Conflict`, and canonical `/* audit.user */` / `/* audit.now */` binds.
- **Organizational data scoping** (Phase 29) — the same `/*%scope … */` directive that confines what
  a caller can *see* confines what a caller can *transition*; the org-unit foundation
  (`OrgUnitStore`) that scope resolution reads is the substrate workflow assignee resolution reads.
- **The core expression language** (`io.tesseraql.core.expr`) — whitelist-only, side-effect-free, no
  method calls — evaluates transition guards.
- **The scheduler** (quartz/timer routes with cluster-safe `tql_job_claim` claiming) — fires
  deadlines, escalations, and reminders exactly once across a cluster.
- **The outbox** (Phase 20/27) — assignment and escalation notifications ride the existing
  at-least-once delivery, visible in the operations console.
- **IAM's managed/SQL realm duality** (`RealmConfig`) — workflow state lives in managed
  `tql_workflow_*` tables or in app-owned tables behind SQL contracts, selected by one config key.

## Decision: native SQL-contract state machine

Roadmap decision point 2 weighed a native SQL-contract implementation against embedding an external
workflow engine (Flowable, Camunda, jBPM). **Resolved 2026-06-15 in favour of the native
implementation.** An external engine would:

- break extension principle 1 (SQL-first): the process state would live in the engine's own schema
  and be mutated by the engine's runtime, not in plain-SQL-tool-runnable 2-way SQL;
- break extension principle 5 (module boundaries): it is a heavy runtime dependency with its own
  persistence, threading, and transaction model to reconcile with Camel and the TesseraQL outbox;
- duplicate machinery the framework already owns — a transaction-scoped write engine, row scoping, an
  expression language, and a cluster-safe scheduler.

The native state machine reuses all of that and adds no runtime dependency. The seam survives the
decision: the `WorkflowStore` SPI and the `kind: workflow` document are where an external engine
*could* later plug in for an application that needs BPMN, without changing the YAML surface — the
same way the PDF codec keeps its engine SPI after adopting openhtmltopdf.

## Three invariants

The framework's extension principles, applied:

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
    command: submit.sql                           # Phase 18 transactional step; /*%scope%/ confines the write
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
  `document.amount > 0`, `principal.role == 'approver'`). It is the same evaluator the Phase 19
  `validate:` rules and `/*%if … */` directives use: comparison and logical operators, dotted paths,
  no function calls, no side effects.
- The **scope** (Phase 29) answers *does this caller have authority over this row?* — a
  `/*%scope … */` directive in the transition's `UPDATE … WHERE`, resolved against the principal,
  parameterized, deny-by-default. The write touches only rows the caller is authorized over.

Keeping them separate is deliberate: the guard expresses the process, the scope expresses
organizational reach. A transition with a satisfied guard but no authorized rows updates nothing and
returns a `409`/`403`, exactly as a scoped write does today. (Why guards do not use the policy
matcher and scopes do: the matcher answers role/permission/claim membership; the guard answers a data
predicate — the same split data-scoping.md draws between `when:` arms and the expression language.)

### Assignee resolution is the dual of a scope

A **scope** maps `principal → predicate over rows`. **Assignee resolution** maps `document → set of
principals` — the same org graph, the opposite direction. So it reuses the same foundation:

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

The `tql_org_closure` join is the Phase 29 managed org-unit foundation consumed unchanged
(`OrgUnitStore.descendants(...)` is the Java seam when resolution is done in code rather than SQL).
In `app` mode the same contract is written against the application's own organization tables.

## A transition is a transactional, scoped write

A transition route compiles to a recipe that, in one transaction:

1. reads the document's current state and checks the transition is declared for it (else
   `TQL-WORKFLOW-3201`, mapped to `409`);
2. evaluates the **guard**; a falsy guard blocks (`422`);
3. runs the transition's **command** — a Phase 18 transactional step list, its `UPDATE` carrying the
   `/*%scope … */` write authority and `/* audit.user */` / `/* audit.now */` binds, with
   `expect: { rows: 1, onMismatch: conflict }` turning a concurrent transition into a `409`;
4. advances the state column (app mode) or the `tql_workflow_instance` row (managed mode);
5. resolves **assignees** and writes the resulting task(s);
6. appends an immutable **history** row (see [Audit trail](#audit-trail));
7. optionally enqueues an **outbox** notification (Phase 20/27) for the new assignee.

All seven happen in the command's transaction, so a transition is atomic and replays safely through
the existing idempotency machinery — the same guarantees `command-json` already gives a write.

## Managed and app modes

Consistent with IAM's managed/SQL realm duality and the Phase 29 org-unit model, workflow state has
two modes, selected by `tesseraql.workflow.mode` (default `app`):

- **`managed`** — the runtime provisions three managed tables and maintains them through a
  `WorkflowStore`:
  - `tql_workflow_instance` — one row per document under workflow (document type, document id,
    current state, tenant);
  - `tql_workflow_task` — open and completed tasks (instance, assignee, candidate group, due-at,
    delegated-to, status);
  - `tql_workflow_history` — the append-only audit trail (instance, transition, from/to state, actor,
    timestamp, note).

  The DDL ships per dialect (`postgres`/`mysql`/`oracle`/`sqlserver`) and is applied by
  `SqlScripts.applyForVendor`, exactly as `tql_org_unit`/`tql_org_closure` are. The store is bound in
  `TesseraqlRuntime` at the same point the `OrgUnitStore` is, only when the mode is `managed`.

- **`app`** (default) — the application owns its workflow tables (or folds state into the business
  table via `stateColumn`); transitions, tasks, and history are app-provided SQL contracts resolved
  the way IAM resolves a SQL-realm contract from its `sqlRoot`. Nothing managed is provisioned.

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

## Task inbox

"The tasks I can act on" is a scope over the task table — direct assignee **or** a candidate group I
belong to **or** a task delegated to me — which is exactly the additive (OR) composition Phase 29
scopes already give:

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

The inbox page is an ordinary `query-html` route whose SELECT applies `/*%scope workflow_inbox … */`,
rendering an `hc-table` fragment through the existing Thymeleaf/`hc-shell` pipeline (so any gap
belongs upstream in Hypermedia Components, mandatory rule 11 — no app CSS over `hc-*`). The fragment
refreshes in place with htmx (`hx-trigger="load, every 30s"`), and the action buttons POST to the
transition routes. Because the *transition* carries its own `/*%scope … */` write authority, a task
appearing in an inbox is necessary but not sufficient — the write still confirms authority
server-side.

## Deadlines, escalation, and delegation

A workflow declares deadlines per state (`within: 48h`). A managed **sweeper job** — a fixed-delay
timer route claimed cluster-safe through `tql_job_claim` (`JobRepository.tryClaimFiring`, the same
machinery scheduled jobs use) — periodically calls `WorkflowStore.overdue(...)` and, for each breach:

- **escalates** by auto-running a declared transition (e.g. `approve`), recording the actor as the
  workflow itself in the history; or
- **reassigns** the task to a fallback resolver (`assignees/dept_head.sql`).

Cluster-safe claiming guarantees an overdue task escalates **exactly once** even across nodes.
**Delegation** is just a transition that reassigns a task to another principal; it appears in the
delegate's inbox immediately (the inbox scope's `delegated_to` arm) and is recorded in history.
Reminders reuse the Phase 20 notification channels.

## Audit trail

Every transition appends one immutable row to the history (`tql_workflow_history` in managed mode, an
app contract otherwise) **inside the transition's transaction** — so the audit record and the state
change commit or roll back together; there is no window where a state advanced without a recorded
reason. A row carries the instance, the transition id, the from/to states, the actor
(`/* audit.user */`, or the workflow itself for an escalation), the timestamp (`/* audit.now */`,
one clock reading per command), and an optional note. History is append-only: the SPI exposes no
update or delete. Downstream consumers (a "request approved" mail, an event for a neighbouring
system) ride the Phase 20/27 outbox, dispatched after commit.

## Governance and testing

A lint family catches a malformed or unreachable state machine before it ships:

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-WORKFLOW-3101` | error | a transition's `from`/`to` (or the workflow's `initial`) names a state not declared in `states` |
| `TQL-WORKFLOW-3102` | error | no `initial` state, more than one `initial` state, or a state unreachable from `initial` |
| `TQL-WORKFLOW-3103` | error | a transition's `guard` is not a valid whitelist expression, or references a path outside `document.*`/`task.*`/`principal.*` |
| `TQL-WORKFLOW-3104` | error | a transition's `command` or an `assign`/`onBreach` `file` is missing under `workflow/` |
| `TQL-WORKFLOW-3105` | warning | a non-terminal state has no outgoing transition (a dead end), or a terminal state has one |
| `TQL-WORKFLOW-3110` | error | `tesseraql.workflow.mode` is set to something other than `managed` or `app` |

The runtime fails closed at execution: a transition not declared for the current state is
`TQL-WORKFLOW-3201` (`409`); a transition whose guard is falsy is `TQL-WORKFLOW-3202` (`422`); a
transition whose scoped write matches no authorized row is a `409`/`403`, exactly as a scoped write
is today — a workflow can never silently no-op a transition.

The **`workflow`** coverage kind declares one item per **transition** across `workflow/` documents; a
transition counts as covered when a declarative suite exercises a route that runs it (the same
SQL-file basis as route and `data-scope` coverage). An app with no workflows reports a 1.0 ratio.
Gate it with `coverage.thresholds.workflow`. (Per-state-path coverage — every state's outgoing
transitions exercised — is a later refinement, mirroring the `data-scope` per-role-path note.)

## Relationship to Phase 29

Phase 29 deliberately factored the org-unit hierarchy as a **shared foundation** and reserved the
seams this phase plugs into, so Phase 28 introduces no second org model
(see [docs/data-scoping.md](data-scoping.md)):

- **Assignee resolution** is the dual of a **scope** — same `tql_org_closure` graph,
  `OrgUnitStore.descendants(...)` the shared Java seam.
- A **task inbox** is a Phase 29 scope over the task table; additive (OR) composition is exactly its
  semantics.
- A **transition** is a Phase 29 scoped write — the `/*%scope … */` in its `UPDATE … WHERE` confines
  it to authorized documents, complementing the guard.

## Delivery slices

Phase 28 ships in slices, each a reviewable PR with CI green, mirroring how Phase 29 shipped:

1. **Workflow core** (delivered) — the `kind: workflow` document (parser, manifest loading,
   `WorkflowDefinition` model), the `WorkflowStore` SPI and `JdbcWorkflowStore` with managed DDL
   (four dialects), the `tesseraql.workflow.mode` toggle, the transition recipe (state check → guard
   → state advance → Phase 18 transactional command → history, in one transaction), the
   `TQL-WORKFLOW-31xx` lint, and the `workflow` coverage kind. No UI, no scheduler. Detailed design
   below.
   - *Acceptance (met):* a draft→submitted→approved/rejected machine drives a business document
     through its transitions; an undeclared transition is rejected with a `409`, a falsy guard with a
     `422`; the workflow is testable via the `workflow` coverage kind, and lint flags a transition
     naming an undeclared state.
2. **Assignee resolution and task inbox** (delivered) — a transition's `assign` contract (a 2-way SQL
   `SELECT` returning `assignee`/`candidate_group` rows, consuming the Phase 29 org-unit foundation
   unchanged) opens a task in the managed `tql_workflow_task` table for the resulting state; the
   prior state's tasks are completed in the same transaction. Authority is framework-enforced rather
   than left to an app-authored scope: a document with open tasks may only be transitioned by a
   caller who holds one (the direct assignee or a candidate group), else `TQL-WORKFLOW-3203` (403) —
   the dual of a scope over the task table, which an app may still author for the inbox query (`my
   open tasks`). The task store (`WorkflowTaskStore`/`JdbcWorkflowTaskStore`) is provisioned whenever
   any transition assigns, independent of the state mode, so one inbox spans every workflow.
   - *Acceptance (met):* submitting opens the resolved approver's task; the assignee can act and the
     task completes; a non-assignee's transition is denied (403); all testable end to end.
3. **Deadlines, escalation, and delegation** — per-state deadlines, the cluster-safe sweeper job,
   escalation (auto-transition) and reassignment, delegation as a reassigning transition, and Phase
   20 notifications on assignment/escalation.
   - *Acceptance:* an overdue task escalates exactly once across a cluster; a delegated task appears
     in the delegate's inbox; the history shows who acted, when, and why for every transition.

Each slice ships its cookbook entry here and keeps the example gallery green; the worked example is a
purchase-request approval application built only with YAML, 2-way SQL, and templates.

### Slice 1 detailed design

This is the implementation-level design for the first slice — the engine, no UI and no scheduler.
Two design points are settled here:

- **The transaction engine is extended, not duplicated.** A transition reuses the Phase 18
  `TransactionalCommandProcessor` rather than a parallel processor, so there is one transaction
  engine, the state advance and history write ride the command's own JDBC `Connection` (the
  connection-threaded idiom `OutboxStore.insert(Connection, …)` already uses), and the guard runs
  *inside* the transaction with no time-of-check/time-of-use gap. The cost is that this touches the
  route-compiler/command path — so it is reviewed, never auto-accepted.
- **Slice 1 ships both modes.** `managed` is complete (`tql_workflow_*` tables behind
  `JdbcWorkflowStore`); `app` is a thin `ColumnWorkflowStore` that keeps state in the business
  table's `stateColumn` and adds no tables (history is an optional app contract). The default `app`
  mode therefore works with zero managed schema.

#### Transitions are compiler-synthesized routes

Authors do not write a route per transition. The `workflow/` document declares the states and
transitions, and the compiler **synthesizes** one transactional-command route per transition — the
way `consume/` documents synthesize `queue-consume` routes. Each transition compiles to a
`direct:workflow.<id>.<transitionId>` route and a `POST {http.basePath}/{key}/{transitionId}`
endpoint, carrying the transition's (or the workflow's) `security`.

The synthesized route runs the existing command machinery with one added input — a `WorkflowBinding`
(store, document type/table/key, `stateColumn`, `from`/`to`, the parsed guard `Expr`, the transition
id). When present, the processor does, in a single transaction:

```text
open connection / begin tx
  ├─ store.ensureInstance(cx, docType, docId, initial, tenant)   # managed only; app no-op
  ├─ document ← SELECT * FROM <table> WHERE <key> = ?            # bound into context as `document`
  ├─ current  ← store.currentState(cx, …)                        # managed: instance row / app: document.<stateColumn>
  ├─ if current != from        → TQL-WORKFLOW-3201 (409)         # legality, in-tx, no TOCTOU
  ├─ if !guard.evalBoolean(ctx) → TQL-WORKFLOW-3202 (422)        # guard over document.* / principal.*
  ├─ validate: rules (existing Phase 19 step)
  ├─ store.advanceState(cx, docType, docId, from, to)            # conditional UPDATE; 0 rows → 409
  ├─ author command step(s)                                      # existing step loop: scoped write + audit + expect
  ├─ store.appendHistory(cx, …)                                  # append-only, same tx
  └─ commit
```

The only change to request binding is that the transition adds a `document` key (the loaded row)
to the execution context the guard and the command SQL already read (`principal`, `body`, `path`,
`tenant`).

#### Workflow document schema and model

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

`assign:` and `deadlines:` are **parsed and linted** in Slice 1 (file-existence checks) but consumed
in Slices 2 and 3 — the YAML shape is fixed from Slice 1 so later slices add no breaking changes. The
model records mirror `ScopeDefinition`'s `@JsonIgnoreProperties` + compact-constructor `copyOf`
normalization (`WorkflowDefinition`, `DocumentSpec`, `StateSpec`, `TransitionSpec`, `AssignSpec`,
`DeadlineSpec`, `OnBreachSpec`), with a `WorkflowFile(Path, WorkflowDefinition)` manifest record. A
`parseWorkflow`/`validateWorkflow` pair (the `requireField`/`EXPECTED_VERSION`/`error` idiom),
`ManifestLoader.loadWorkflows` (walk `workflow/`, `.yml`, `requireInside`, sorted), and an
`AppManifest.workflows()` field complete the plumbing.

#### The Slice 1 `WorkflowStore`

Slice 1 implements the instance-and-history subset of the SPI sketched above (task methods arrive in
Slice 2/3), every write threaded on the caller's `Connection`:

```java
public interface WorkflowStore {
    String currentState(Connection cx, String docType, String docId);                       // null if none
    void   ensureInstance(Connection cx, String docType, String docId, String initial, String tenantId);
    int    advanceState(Connection cx, String docType, String docId, String from, String to); // rows affected
    void   appendHistory(Connection cx, WorkflowHistory entry);                              // append-only
}
```

- **`managed`** — `JdbcWorkflowStore` (modelled on `JdbcOrgUnitStore`); `advanceState` is
  `UPDATE tql_workflow_instance SET current_state = ?, updated_at = ? WHERE doc_type = ? AND doc_id = ? AND current_state = ?`
  (0 rows ⇒ the caller raises `409`), and `ensureSchema()` calls
  `SqlScripts.applyForVendor(ds, JdbcWorkflowStore.class, "/tesseraql/db/migration/workflow/V1__workflow.sql")`.
- **`app`** — `ColumnWorkflowStore`: `currentState` reads `document.<stateColumn>`, `advanceState` is
  `UPDATE <table> SET <stateColumn> = ? WHERE <key> = ? AND <stateColumn> = ?`, and
  `ensureInstance`/`appendHistory` are no-ops (history is an optional app contract). No managed
  schema.

The managed DDL ships `tql_workflow_instance` and `tql_workflow_history` only (the task table is
Slice 2), in four dialects under `db/migration/workflow{,-oracle,-sqlserver}/`. To stay portable it
uses inline `unique (doc_type, doc_id)` rather than a separate `CREATE INDEX` (MySQL does not accept
`CREATE INDEX IF NOT EXISTS`); the Oracle variant uses plain `CREATE` + ORA-00955 tolerance and the
SQL Server variant guards with `if object_id(...) is null`, exactly as the org-unit DDL does.

The runtime binds the store just after the org-unit block in `TesseraqlRuntime`, only when the app
declares workflows: `managed` mode constructs `JdbcWorkflowStore` and calls `ensureSchema()`, `app`
mode binds `ColumnWorkflowStore`; the bean name is `TesseraqlProperties.WORKFLOW_STORE_BEAN`, and
`WorkflowSettings.from(config)` reads `tesseraql.workflow.mode` (default `app`) just like
`OrgUnitSettings`.

#### Lint, runtime errors, and coverage

`AppLinter.lintWorkflows` (a sibling of `lintScopes`) emits the `TQL-WORKFLOW-31xx` family:

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-WORKFLOW-3101` | error | a transition's `from`/`to` (or the workflow's `initial`) names a state not in `states` |
| `TQL-WORKFLOW-3102` | error | no `initial`, more than one `initial`, or a state unreachable from `initial` |
| `TQL-WORKFLOW-3103` | error | a `guard` is not a valid whitelist expression, or references a path outside `document.*`/`task.*`/`principal.*` |
| `TQL-WORKFLOW-3104` | error | a `command`/`assign.file`/`onBreach` file is missing under `workflow/` |
| `TQL-WORKFLOW-3105` | warning | a non-terminal state has no outgoing transition, or a terminal state has one |
| `TQL-WORKFLOW-3106` | error | mode mismatch: `app` needs `document.{table,key,stateColumn}`, `managed` needs `document.{type,table,key}` |
| `TQL-WORKFLOW-3110` | error | `tesseraql.workflow.mode` is not `managed` or `app` |

The runtime fails closed under a new `TqlDomain.WORKFLOW`, so its codes match the lint family rather
than borrowing `TQL-SQL-*`: an illegal transition for the current state is `TQL-WORKFLOW-3201`
(`409`), a falsy guard `TQL-WORKFLOW-3202` (`422`). The `workflow` coverage kind
(`ManifestCoverage.workflow`, registered in `AppTestRunner.coverageKinds` right after `dataScope`,
and added to `CoverageThresholdResolver.KINDS`) declares one item per transition and covers it when a
suite exercises the synthesized transition route — the same `testedSqlPaths` basis as `route` and
`data-scope`.

#### Touch list and tests

| Module | New | Changed |
| --- | --- | --- |
| `tesseraql-core` | `workflow/WorkflowStore`, `TqlDomain.WORKFLOW` | — |
| `tesseraql-yaml` | `model/Workflow*`, `manifest/WorkflowFile`, `workflow/WorkflowSettings` | `SimpleYamlParser`, `ManifestLoader`, `AppManifest`, `AppLinter` |
| `tesseraql-camel-components` | — | `TesseraqlProperties` (bean constant) |
| `tesseraql-compiler` | `binding/WorkflowBinding` | `RouteCompiler` (synthesis), `TransactionalCommandProcessor` (optional binding), `RequestBinder` (`document` key) |
| `tesseraql-operations` | `workflow/JdbcWorkflowStore`, `db/migration/workflow{,-oracle,-sqlserver}/V1__workflow.sql` | — |
| `tesseraql-camel-runtime` | `ColumnWorkflowStore` | `TesseraqlRuntime` (binding) |
| `tesseraql-test-core`, `tesseraql-report` | — | `ManifestCoverage`, `AppTestRunner`, `CoverageThresholdResolver` |
| `examples/` | `purchase-request-app` | — |

`JdbcWorkflowStore` is covered by Testcontainers ITs across the four dialects (in
`tesseraql-camel-runtime`, where the SAML-replay store IT lives — a leaf module gets no H2),
asserting that `advanceState` affects zero rows on a stale/illegal transition and that history is
append-only. The worked example carries a declarative suite driving draft→submitted→approved and
asserting `409` on an undeclared transition, `422` on a falsy guard, and `409` on a concurrent
transition (the `advanceState` zero-row race), with the `workflow` coverage kind reporting every
transition exercised.

## Module placement

| Concern | Module |
| --- | --- |
| `WorkflowDefinition` model, `WorkflowStore` SPI, guard evaluation | `tesseraql-core` (dependency-free) |
| `JdbcWorkflowStore`, managed DDL | `tesseraql-operations` |
| Transition recipe compilation, inbox templates | `tesseraql-compiler` |
| `kind: workflow` parsing, `TQL-WORKFLOW-31xx` lint | `tesseraql-yaml` |
| Mode wiring, the sweeper route | `tesseraql-camel-runtime` |
