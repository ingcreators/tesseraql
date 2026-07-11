# Workflow delegation and absence — who acts for whom, and when

The [approval workflow](approval-workflow.md) gives approvals a per-task escape
hatch: the **current assignee** may hand one task they already hold to someone else
(`POST …/delegate/{to}`). What that deliberately does not answer is absence: an
approver on leave should not need to hand over tasks one by one — and tasks that
have not arrived yet cannot be handed over at all. Delegation adds the standing
rule: **an absence window with a delegate, applied where assignees are resolved**,
with a trail that always shows who acted for whom.

## The security stance

- **Delegation redirects assignment. It never borrows identity.** The delegate becomes
  the task's assignee and acts **as themselves**, within their own roles, permissions,
  and scopes. `canAct`, guards, and row scoping are untouched; there is no
  impersonation surface anywhere in this design.
- **One hop, never a chain.** If A delegates to B and B is also absent, a task for A
  lands with B regardless. Resolution consults exactly one rule — the assignee's — so
  loops (A→B→A) are impossible by construction, not by cycle detection.
- **Only the delegator writes their own rule.** The [account surface](account.md)'s
  construction invariant (the subject is always the session principal's) applies;
  nobody can delegate someone else's approvals. Self-delegation is refused.
- **The trail is structural.** The task row records `delegated_from` at assignment;
  tasks persist after completion (`DONE`, `completed_by`). Who was meant, who received,
  and who acted are three columns on one immutable-in-practice row, joined to the
  transition history's actor — no new history format, nothing to forget to write.

## Setting the rule

One standing rule per subject, self-served from the account page ("Out of office"):

```sql
create table if not exists tql_workflow_delegation (
  tenant_id        varchar(64)  not null,
  subject          varchar(255) not null,
  delegate_subject varchar(255) not null,
  starts_at        timestamp    not null,
  ends_at          timestamp    not null,
  created_at       timestamp    not null,
  primary key (tenant_id, subject)
);
```

`DelegationStore` SPI in core (`active(tenant, subject, at)` → the delegate while
`starts_at ≤ at < ends_at`, else empty; `put`/`clear`), `JdbcDelegationStore` in
operations — outside the Flyway component set, `ensureSchema`-only. The account
card validates: a window with both ends, ending in the
future, and a delegate that is not the subject; when the managed identity realm is
present the delegate is entered as a **login id** and resolved to its subject
(unknown → `TQL-ACCOUNT-4802`), otherwise a raw subject is accepted and the page says
so. The login-id resolution applies when the identity **schema answers** — the
identity beans exist even without it, so a failed lookup falls back to the raw
subject rather than failing the card.

## Resolution at assignment time

Everywhere a **direct assignee** is chosen, the engine asks once: *is this subject
absent right now?* If so, the task opens for the delegate, with `delegated_from`
recording the intended assignee. The three call sites — all funnels into the same
store methods — are:

1. a transition's `assign:` resolver rows (the `openTask` site);
2. the sweeper's `onBreach: reassign` fallback assignee;
3. the target of the per-task `delegate/{to}` operation (handing a task to
   someone absent forwards it once, same one-hop rule).

**Candidate groups are untouched** — a pool does not go on leave. **Existing tasks are
untouched** — the window redirects what is assigned during it; tasks already held are
exactly what the per-task delegate operation and the deadline sweeper are for.

The task inbox shows the provenance (“for &lt;delegator&gt;”), and the `assigned`
reminder notification carries the *effective* assignee — the person who must act.

Put together, on a typical approval app: an approver sets an absence with a
delegate; a request submitted during the window lands in the delegate's inbox
marked "for" the approver; the delegate approves it **as themselves** and the trail
shows who acted for whom; after the window ends new requests reach the approver
again — no permission ever borrowed, no chain ever followed.

## The operator surface

The IAM admin has a read-only **Active delegations** panel
(`/_tesseraql/admin/delegations`): who is absent, who covers, until when — the
operator's answer to "why did this land with X?".

## Not currently supported

- Multi-rule calendars, partial-day windows, per-workflow delegates: one subject, one
  window, one delegate. The table shape leaves room; the semantics stay small.
- Delegating candidate-group membership.
- Retroactive reassignment of already-open tasks when a window begins (the per-task
  operation covers it deliberately).
- Admin-imposed delegation on behalf of another user — a possible later,
  separately-audited surface; today the rule is strictly self-service.
