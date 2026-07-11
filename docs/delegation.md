# Workflow delegation and absence

The [approval workflow](approval-workflow.md) gives approvals a per-task escape
hatch: the **current assignee** may hand one task they already hold to someone else
(`POST …/delegate/{to}`). What that deliberately does not answer is absence: an
approver on leave should not need to hand over tasks one by one — and tasks that
have not arrived yet cannot be handed over at all. Delegation adds the standing
rule: **an absence window with a delegate, applied where assignees are resolved**,
with a trail that always shows who acted for whom.

## Setting the rule

The rule is self-served from the **Out of office** card on the account page
(`/_tesseraql/account`, the [account surface](account.md)). It asks for three
things: the delegate, and the **From** and **Until** ends of the absence window
(UTC). **Save** records the rule, **Clear** removes it, and while a rule exists the
card shows the delegate and window, marked as active when the window is open.

- When the managed identity schema is installed, the delegate is entered by
  **login id** and resolved to its subject (an unknown login is refused with
  `TQL-ACCOUNT-4802`); otherwise enter the raw subject — the page says which it
  expects.
- The window must have both ends and end in the future, and the delegate cannot be
  yourself.
- The card appears wherever the approval workflow machinery is in use; no
  configuration is needed.

One standing rule per subject — one window, one delegate. The runtime creates the
backing table, `tql_workflow_delegation`, automatically; for reference:

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

## Resolution at assignment time

Everywhere a **direct assignee** is chosen, the engine asks once: *is this subject
absent right now?* If so, the task opens for the delegate, with `delegated_from`
recording the intended assignee. The three call sites — all funnels into the same
resolution — are:

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

## The security stance

- **Delegation redirects assignment; it never borrows identity.** The delegate becomes
  the task's assignee and acts as themselves, within their own roles, permissions,
  and scopes — `canAct`, guards, and row scoping are untouched, and there is no
  impersonation surface.
- **One hop, never a chain.** If A delegates to B and B is also absent, a task for A
  lands with B regardless: resolution consults exactly one rule — the assignee's — so
  loops are impossible by construction.
- **Only the delegator writes their own rule.** Nobody can delegate someone else's
  approvals, and self-delegation is refused.
- **The trail is structural.** The task row records who was meant (`delegated_from`),
  who received (the assignee), and who acted (`completed_by`); tasks persist after
  completion, so nothing has to be remembered to be written.

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
