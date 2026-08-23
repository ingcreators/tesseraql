# IAM Admin

IAM Admin is the console for **people**: who has an account, who is signed in right now,
and who is standing in for whom. It is the administrative counterpart to the self-service
[account surface](account.md), where users manage their own profile and sessions.

Open it at `/_tesseraql/admin/users`. In a hosted stack it answers once, at the stack's
origin — the identity store is stack-scoped, so there is one admin door to it, exactly as
there is one sign-in door ([hosting.md](hosting.md)). An unhosted runtime serves its own
copy as before.

## Who can open it

| Granted atom | Grants |
| --- | --- |
| `tql.iam.admin.view` | The user list, user detail, the sessions page, and delegations. |
| `tql.iam.admin.write` | Inviting, enabling and disabling users, and revoking sessions. |

These are framework atoms — permission codes in the identity store, granted to a user
directly or bundled into whatever roles your deployment defines
([authentication.md](authentication.md)). They are store-wide exact strings: the store has
no per-application axis, so neither do they.

## Users

The user list shows the accounts in the identity store with their status. Open one to
reach the detail page, which is where most administrative work happens.

**Invite a user.** The invitation creates the account and sends the new user a link to set
their own password. Nobody types a password on someone else's behalf. The invitation and
reset flows are described in [credential-lifecycle.md](credential-lifecycle.md).

**Disable a user.** Disabling ends access immediately: it marks the account disabled *and
invalidates every session that account holds*. A disabled account is not "disabled at next
login" — the browser it left open stops working on the next request.

**Enable a user.** Restores access. The user signs in again as normal.

## Sessions

Two views cover live sessions:

- The **user detail page** lists the sessions that one account holds, with the time each
  was created, when it was last seen, and the user agent and address it was presented
  from. You can end one session, or end all of that account's sessions at once.
- The **sessions page** (`/_tesseraql/admin/sessions`) lists live sessions across every
  subject, for the question "who is signed in right now".

Sessions are addressed by a handle, never by the cookie value, so nothing on these screens
can be replayed as a credential. The address is recorded exactly as presented by the
request; the framework does not resolve it to a location.

Where `tesseraql.sessions.idleTimeout` is configured, a session also expires after that
much inactivity, inside its absolute lifetime. The scaffolded configuration sets it.

## Applications

The applications page (`/_tesseraql/admin/applications`) answers "who may do what in this
application", one page per application in the stack. Each application's page lists:

- who may **use** it — the holders of its `tql.app.use` grant, with holders of the
  `tql.app.use.*` wildcard shown under their own rows;
- who may **see** and **act on** its operations, **deploy** it, and **edit it in Studio**;
- the application's own **permission codes** — the codes carrying its name as their first
  segment — and who holds each one, through which role.

The views are read-only and derived entirely from the identity store. A `sql` realm that
does not provide the optional grant-view contracts sees the pages degrade with a notice
rather than fail.

## Roles and grants

The roles page (`/_tesseraql/admin/roles`) lists the store's roles and creates new ones.
A role either belongs to one application — its code then carries that application's name
as its first segment, like `orders.approver` — or is stack-wide, the deployment's own
vocabulary like a department. An application can also *declare* its roles in its own
configuration (`tesseraql.security.roles`); declared roles converge into the store at
boot, and one whose declaration went away is badged **no longer declared** here —
assignments are kept, and re-declaring it revives it. The user detail page edits one person's grants:

- **Role assignments**: assign or unassign a role, optionally with a **validity window**
  (a from and until date-time). An expired or not-yet-started assignment does not reach
  the user's next sign-in; a future-dated one arrives at the first sign-in after its
  start.
- **Direct permissions**: grant a permission code to one person directly — the bounded
  exception that needs no synthetic role — with the same optional window.

Every write is gated by the realm's `roleManagement` capability; a realm that does not
allow it renders these editors read-only and refuses the write. Grant changes take
effect at the affected user's next sign-in, like every grant change.

## Attributes and assignment rules

The user detail page also edits a person's **attributes** — free-form named values like a
department or a title, which reach the application as `principal.claim.<name>`.

Attributes can also **arrive with the user** instead of being typed here. SCIM provisioning
captures the enterprise extension's org attributes (`department`, `division`, `costCenter`,
`employeeNumber`, `manager`) plus any additional paths `tesseraql.scim.attributes.map`
declares, on create and update, when `tesseraql.scim.attributes.enabled` is on — the
capture assumes the SCIM contracts manage this identity store's users, so the SCIM
resource id is the user id. SAML and OIDC logins re-sync their configured attribute maps
at every sign-in ([saml.md](saml.md#attribute-mapping-and-user-linking),
[authentication.md](authentication.md#openid-connect-relying-party)). All three sources
share one write discipline: a mapped value that stops arriving is deleted, and everything
unmapped is discarded.

The rules page (`/_tesseraql/admin/rules`) turns attributes into roles: a rule grants one role when
every condition matches, with condition kinds `eq`, `in`, `neq`, `not-in`, `group`
(membership in a store group) and `subtree` (the attribute sits under an org unit, via the
managed org foundation). Rules apply at each user's next sign-in and their assignments
carry rule provenance — a manually assigned role always survives, and a rule's assignment
goes away when the rule stops producing it. **Recompute now** (one user from their detail
page, everyone from the rules page) applies rule edits without waiting for sign-ins.

## Groups

The groups page (`/_tesseraql/admin/groups`) creates a group, and a group's own page edits
its two halves: **who is in it**, and **what it delivers**. A group is a bundle of roles
with a membership — the way to give the same access to many people once, and to take it
away once.

Membership carries the same **validity window** as every other assignment. Somebody added
until the end of a secondment stops being a member then, with nothing to remember: the
window is what ends it. The member list on the group page is unfiltered on purpose — an
administrator needs to see a membership that has not started yet, or has ended, in order to
edit it — while the member count on the list page counts only live ones.

Joins and leaves are recorded in the [grant history](#grant-history). Deleting a group
empties its memberships first and records a leave for everybody who was in it, so nobody's
access changes without a trail saying so.

Changes reach people at their next sign-in, like every grant change.

**SCIM provisioning into the managed groups.** An identity provider can manage this same
store over `/scim/v2/Groups` with no SQL written at all. Set
`tesseraql.scim.groups.enabled`, configure none of the per-operation
`tesseraql.scim.groups.<op>` keys, and the bundled contract set applies. `id` maps to
`group_id` (minted as `grp-<uuid>`), and `externalId` maps to `external_id`.
`displayName` maps to `group_name` **and** `group_code`: the code is what assignment rules
join on, so it follows the name an administrator recognises — which also means a rename at
the provisioning client renames the code. Declaring **all** of the operation keys means
your own schema instead; declaring only some is refused at boot naming the missing keys,
because two schemas mixed one statement at a time looks like a framework bug rather than a
configuration one. Members provisioned this way are `tql_user_groups` rows with
`source = 'scim'`, riding the same windows and the same sign-in resolution as any other
membership.

## Eligible roles

An **eligibility** says a person may take a role when they need it, and grants nothing
until they do. It never reaches their principal: an eligible role is absent from their
roles, absent from their permissions, and invisible to every policy — which is the whole
difference between "may take" and "holds".

Make somebody eligible from the **Eligible roles** card on their detail page, naming a
limit in minutes and whether a reason is required. They then take the role from their own
[account page](account.md), for a window up to that limit, and it **expires by itself** —
no revocation to remember, because a validity window is what ends it.

Taking one is live immediately in the session that took it. Their other sessions see it at
their next sign-in, and nothing else about a signed-in principal refreshes mid-session.

Every elevation is recorded in the [grant history](#grant-history) with its reason and its
window, and it passes the same separation-of-duties check as any other grant — a temporary
grant is exactly the kind a constraint exists to catch.

## Separation of duties

The constraints page (`/_tesseraql/admin/constraints`) declares roles nobody may hold at
once — the buyer who must not also approve, the developer who must not also release. A
constraint names two or more role codes and a severity: **block** refuses the grant,
**warn** records the conflict and lets it through.

The constraint is checked where grants are made, which is the only place it can be:

- **An administrator's assignment is refused** and the answer names the constraint and the
  role already held, so the next step is obvious — revoke one side first.
- **An assignment rule's role is withheld** rather than refused. Rules converge at sign-in,
  and refusing there would lock somebody out of the product because two attribute rules
  disagree. Nothing is written, and the constraint's effect shows in the violation report.

**Existing violations** are listed below the constraints. A constraint added to a store
where people already hold both sides has violations the day it is created; they are
reported, never resolved automatically, because revoking somebody's access is a decision an
administrator makes — and the [grant history](#grant-history) records who made it.

This is *static* separation of duties. The *dynamic* half already holds without a
constraint: a person acts as one role at a time per application, chosen at use time, and
the audit records which capacity acted ([application roles](authentication.md)).

## Context conditions

The conditions page (`/_tesseraql/admin/conditions`) narrows *where* and *when* a held role
may be used. A condition is one of two kinds:

| Kind | Value | Example |
| --- | --- | --- |
| `network` | a CIDR block, or a bare address for a single host | `10.0.0.0/8`, `203.0.113.7` |
| `hours` | `[<days> ]<HH:MM>-<HH:MM>` in the deployment's zone | `MON-FRI 09:00-18:00`, `SAT,SUN 10:00-16:00`, `09:00-18:00` |

**Within one kind any condition admits; across kinds every kind must.** Two networks are two
offices, and a role usable from either is what naming both means. A network *and* an hours
condition are two separate requirements. A range whose end is at or before its start runs past
midnight, and its days are the days the window *opens*: `MON-FRI 22:00-06:00` admits Saturday
at 05:00 (Friday's window, still open) and refuses Monday at 05:00.

Hours are read in `tesseraql.security.conditions.zone`, defaulting to the JVM's zone. Name one
if your servers may move: "login hours" means the business's hours.

A grant whose conditions this request does not satisfy is **dropped from the active view** —
its role leaves `roles` and its permissions leave `permissions` unless another surviving grant
or a direct grant delivers them, it is absent from the role picker, and acting as it through
its `/_as/` address is refused like any unheld role. Nothing is revoked: the grant is intact
and the same person from the office at 10:00 has it in full.

**A condition narrows and never widens, and it is not the network boundary.** The address it
judges is whatever the edge presented, so the worst a spoofed one can do is take capability
away. The enforceable answer to "who may sign in from where" is the deployment allow-list
[`tesseraql.security.network.allow`](authentication.md#where-a-session-may-be-established-from),
checked before a session exists. Use a condition to keep a *capacity* inside the office or
inside business hours; use the allow-list to keep *everybody* out of everywhere else.

A condition value that could never be satisfied — a malformed block, an unreadable time range,
an unknown kind — is refused when it is written. The evaluator fails closed on one, so an
accepted typo would silently close the role to everybody rather than open anything.

Conditions ride the grant into the principal at sign-in and are evaluated per request, so a
condition added now reaches an existing session at its next sign-in — the same rule the rest of
the frozen principal follows.

## Access requests

The requests page (`/_tesseraql/admin/requests`) is the approver's side of self-service
access. It shows only requests for roles **you** own — by name, or through a group you are
in — so it cannot offer you a decision you are not entitled to make.

A role becomes requestable by having an **owner**, recorded on the same page. A role with no
owner cannot be asked for at all: that is the answer to "who approves this", rather than
falling back to whoever happens to administer the store.

Approving lands the grant through the same write an administrator uses, so it passes the
separation-of-duties check and appears in the [grant history](#grant-history) naming the
request that caused it. When the requester asked for a duration, the grant is **time-boxed**
to it. Rejecting changes nothing about what is held, so it leaves no grant row — the request
itself is the record. A decided request is final; two approvers acting at once produce one
decision, not two grants.

People ask from their own [account page](account.md).

## Access reviews

The reviews page (`/_tesseraql/admin/reviews`) runs the periodic question: *is all of this
still needed?* Opening a campaign takes a **snapshot** of who holds what — every role held
by any path, and every direct permission grant — and each becomes one item to decide.

The snapshot is the point. A campaign that read live grants would ask reviewers about a
moving target and could never answer "what did we certify in Q3". Because it snapshots, it
answers both, and the gap between opening and closing stays visible: an item whose grant has
already gone by the time the campaign closes is recorded **stale** rather than revoked,
because claiming to have removed something that was not there is a false entry in a record
whose whole value is being true.

Each item shows how the grant **arrived** — an administrator's assignment, a rule, a group —
because a role somebody was never individually given is a different question for a reviewer.
Decide **keep** or **revoke**, with a note.

**Close and apply** executes every revoke through the same write an administrator uses, so
each revocation is validated exactly as usual, appears in the [grant history](#grant-history)
like any other change, and names the campaign that decided it in the **Cause** column. Once
closed, a campaign takes no more decisions: its items are the record of what was certified.

Scope is the whole store or one application.

## Grant history

The history page (`/_tesseraql/admin/history`) answers when a person got a role or a
permission, and who decided. Every change to what somebody holds is recorded as it is
made: the administrator's own edits from the pages above, and the assignment rules'
converge at sign-in. The actor column names the administrator who decided, or `rule` when
a rule produced the change with no person deciding.

Filter by user id or by application, or open one person's trail from the **Grant history**
card on their detail page. The trail is append-only — nothing edits or removes a row — and
a realm whose contracts do not include it keeps its grant writes and says here that it
holds no history.

This is not the [route audit](ops-console.md#audit): that records HTTP calls, and the rule
converge is not one. Recording the grant where the grant is made is what makes the trail
complete.

## Delegations

The delegations page shows who is currently acting for whom, and over what period.
Delegation lets an approver hand their workflow tasks to a colleague while they are away,
and this page is where an administrator sees the standing arrangements.

Delegation is declared and driven by the workflow layer; see
[delegation.md](delegation.md) for the model and [approval-workflow.md](approval-workflow.md)
for the approvals it applies to.

## What IAM Admin does not do

- **Roles and policies are declared in the application**, not edited here. They live in
  YAML and travel through review and promotion like the rest of the application
  ([authentication.md](authentication.md), [promotion.md](promotion.md)).
- **Federated users come from the identity provider.** With SAML ([saml.md](saml.md)) or
  SCIM provisioning, the directory is the source of truth for accounts and group
  membership; IAM Admin shows the result.
- **Row-level access** is [data scoping](data-scoping.md) and
  [multi-tenancy](multi-tenancy.md), declared in the application.

## Next

- [authentication.md](authentication.md) — realms, roles, policies, and how a principal is
  resolved.
- [account.md](account.md) — the self-service surface users see.
- [credential-lifecycle.md](credential-lifecycle.md) — invitations, password reset, and
  rate limiting.
