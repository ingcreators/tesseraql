# Access governance

Implementation design for the remainder of the application role model: the six entries the
[per-application roles](application-roles.md) design recorded as *deferred with direction*.
Each named the shape it would take, and this document is where those shapes become
structure. Together they answer four questions the shipped model leaves open — **who may
grant**, **what a person may not hold at once**, **who reviews what is held**, and **from
where a held role may be used**.

The pieces match mainstream practice deliberately, so each can be judged against a known
shape: mutually exclusive role sets are SAP GRC's and Oracle's static separation of duties;
eligible-versus-active roles are Entra Privileged Identity Management; grant history and
certification campaigns are the IGA shape every access-review tool implements; request,
approve, time-boxed grant is Entra entitlement management; per-application administration
is the Entra app owner, the Okta resource set and the kintone app admin; and network and
hour conditions on a role are the Salesforce profile's login-IP and login-hours pair.

Written 2026-08-20, before implementation, measured against main at #913. The application
role model is complete through slice 5, so `tql_roles.application`, `tql_user_permissions`,
validity windows, `source` provenance, `Principal.roleGrants`, the `_as` activation address
and the `acting_role` audit column are all shipped ground this design stands on.

**Status. Slice 1 is shipped** (the grant trail): `tql_grant_history` across all four
dialects, the two optional contracts, both write paths recording, the history page and the
per-user card. Implementation decisions recorded: the actor reaches `RoleAdmin` as a
declared route parameter (`actor: principal.loginId`) — route-resolved and never
caller-writable, the pattern the role picker established; an unnamed caller records as
`unknown` rather than as a blank column, which would read as a bug in the trail; the
re-assign that revokes and grants leaves **one** row, because it is one decision; and the
read joins the role's current application beside the recorded one, so a role that moved
applications shows both truths instead of one rewritten one.

**Slice 2 is shipped** (separation of duties): the two constraint tables across all four
dialects, both checkpoints, the constraints page and the existing-violation report.
Implementation decisions recorded: a constraint is created with at least two codes and each
code must name a role that exists, because a constraint that can never fire reads as
protection that is not there; both sides of a conflict *among rule-produced roles* are
withheld, since keeping whichever the iteration reached first would grant an arbitrary
capacity and call it a decision; a withheld role is **revoked** rather than merely not
granted, or a constraint added today would never take effect on the people it was added
for; and the refusal's constraint name and conflicting codes ride `TqlException.details`,
the envelope's one declared-safe channel. **A defect surfaced and is fixed here**: the IAM
domain mapped only 4030 to an HTTP status, so 4031/4032/4033 reached callers as
"Internal Server Error" with their messages suppressed.

**Slice 3 is shipped** (eligibility and elevation): `tql_role_eligibility` across all four
dialects, the elevate and end-early actions, `SessionStore.replacePrincipal` in both
stores, the account card and the administrator's eligibility editor. Implementation
decisions recorded: elevation is a `POST /_tesseraql/account/elevate` **Java route** beside
the sign-out endpoints, because only that layer reads the session cookie — and it elevates
the session's own subject, so the request names no target to validate; the elevated
assignment is stamped `source = 'elevation'` so ending one early can key on that provenance
and never touch an administrator's standing grant of the same role; re-granting an
eligibility with different limits is a revoke and a grant rather than an upsert, so the
caller sees one shape on every dialect; and `requires_approval` is carried in the schema
but unused until slice 6, where an approval-gated elevation becomes a request.

**Slice 4 is shipped** (groups): eleven contracts, `source` and the membership window on
`tql_user_groups` across all four dialects with the resolution reads filtering on it, and
the groups and per-group pages. Implementation decisions recorded: the member list on a
group's page is unfiltered while the list page's member count is not, because an
administrator edits memberships that a signing-in user would never see; a membership or a
bundle write that affects zero rows is a refusal rather than a silent no-op; deleting a
group empties its joins before dropping it, since the standard schema carries no foreign
keys to catch a membership pointing at a group that is gone. The managed-store SCIM Group
contract set is deferred to slice 4b — see structural decision 4 for the portability
measurement that forced it.

## The one correction the measurement forced

The deferred entry for group provisioning reads "no SCIM Groups endpoint, no admin UI, no
write contracts". **The endpoint exists.** `tesseraql-scim` ships `ScimGroupService`,
`ScimGroupContract`, `ScimGroupPatch` and the outbound `ScimGroupProvisioner`, mounted at
`/scim/v2/Groups` when `tesseraql.scim.groups.enabled` is true. What is missing is narrower
and more useful to say plainly: the endpoint runs only on **nine hand-authored SQL files**
the deployment must supply, so it cannot provision the managed identity store without a
deployment writing SQL against TesseraQL's own schema. The admin UI and the identity write
contracts are genuinely absent, as recorded.

Everything else in the ledger measured as described.

## What exists today, measured

**Grant writes have exactly two paths, and neither leaves a record of its own.**
`RoleAdmin.assignRole`/`unassignRole`/`grantPermission`/`revokePermission` are the admin
path; `RoleRules.materialize` is the rule path, converging `source = 'rule'` rows at
sign-in and at the two recompute actions. The route audit records the admin path (an HTTP
call with a principal) and cannot record the rule path at all, because a sign-in
materialization is not a route invocation. There is no place a question like "when did this
person get this role, and who decided" can be asked.

**Nothing anywhere compares two grants.** No constraint table, no check at either write
path. A person can hold `orders.buyer` and `orders.approver` with nothing noticing.

**Every grant is either held or not held.** The `starts_at`/`ends_at` window shipped in
slice 2 makes a grant time-boxed, but a *future* grant is indistinguishable from an
*eligible* one: both are rows that are not yet active. There is no way to record "this
person may take this role when they need it" without granting it.

**`tql_groups`, `tql_user_groups` and `tql_group_roles` are read by three contracts and
written by none.** `find-groups-by-user-id` feeds the principal, `find-roles-by-user-id`
and `find-permissions-by-user-id` each carry a group arm. The identity pack has no
create-group, no membership write, no group-role write. `tql_user_groups` has no window
columns, unlike both other assignment tables.

**IAM Admin is store-wide or nothing.** Every page and every write in the bundled
`iam-admin` app is gated by `tql.iam.admin.view` or its write twin. Slice 1's
per-application pages (`/_tesseraql/admin/applications/{name}`) are read-only views over a
store-wide grant.

**The network substrate is shipped and unreachable from identity.** `TrustedProxies` in
`tesseraql-camel-runtime` parses CIDR blocks and matches peer addresses correctly, and it
is package-private in a module `tesseraql-identity` and `tesseraql-security` do not depend
on. `SessionStore.ClientInfo` records `remoteAddr` at login, and its own javadoc says the
value is informational — "an edge that does not strip inbound `X-Forwarded-For` lets a
client spoof it".

**The workflow engine governs app-owned documents through app-authored SQL.**
`TransitionExecutor` advances a state column and runs the transition's command as scoped
2-way SQL in one transaction. It has no seam for calling a framework service, by design.

## Structural decision 1: every grant change writes a history row, at both write paths

`tql_grant_history` is append-only and carries one row per change:

```text
tql_grant_history(event_id, occurred_at, actor, subject_user_id, change_kind,
                  subject_code, application, source, starts_at, ends_at, reason,
                  correlation)
```

`change_kind` is one of `role-granted`, `role-revoked`, `permission-granted`,
`permission-revoked`, `group-joined`, `group-left`. `subject_code` is the role or
permission or group code the change is about. `source` repeats the assignment's provenance
(`admin`, `rule`, `elevation`, `request`, `review`), so the trail says which mechanism
acted, not only which person. `correlation` names the elevation, request or review that
caused the change, and is null for a direct admin edit.

**Why not the route audit.** The route audit answers "which HTTP call happened". Two of the
five sources are not HTTP calls: the sign-in materialization runs inside principal
resolution, and the review close runs as a batch. A trail that silently omits the automatic
paths is worse than none, because it reads as complete.

**The actor.** `RoleAdmin`'s write methods gain an `actor` parameter, threaded from the
route as a declared parameter (`actor: principal.loginId`), exactly as slice 5 threaded
`roleGrants` into the picker. The rule path records the actor `rule` and the source `rule`;
there is no person to name, and naming the signing-in user would be a lie about who decided.

**Degradation.** Writing history is a `roleManagement` contract like every other grant
write, so a realm without the trail installed degrades: the history page reports the store
does not keep one, and grant writes are unaffected.

*Corrected in implementation (slice 4).* This section first said a history write failure
propagates, because losing the record of a change that happened is not tolerable. That is
right about a *present* trail and wrong about an *absent* one, and the store cannot tell
those apart from the failure alone. The standard schema is applied with
`create table if not exists`, so an existing store gains the table only when the operator
re-runs it — and propagating would mean every grant write in that deployment fails until
they do. Refusing all administration over an uninstalled table is the wrong failure, the
same lesson the declared-role reconciler learned about never failing boot on an uninstalled
store. An uninstalled feature degrades; anything else still propagates.

## Structural decision 2: separation of duties is a constraint set, checked where grants are made

```text
tql_sod_constraints(constraint_id, constraint_name, severity, description)
tql_sod_constraint_roles(constraint_id, role_code)
```

A constraint names two or more role codes that are mutually exclusive. A person **violates**
it by holding two or more of the set at once, by any path. `severity` is `block` or `warn`.

**The two checkpoints are the two write paths**, exactly as the deferral predicted:

- **The admin write.** `RoleAdmin.assignRole` evaluates the constraint set against what the
  person would hold after the assignment. A `block` violation refuses the write with
  TQL-IAM-4034, naming the constraint and the conflicting role. A `warn` violation writes
  the grant and returns the warning for the page to show.
- **The rule converge.** `RoleRules.materialize` evaluates the same way, and a `block`
  violation **withholds the rule-produced role** rather than refusing anything. This runs
  inside sign-in: refusing here would lock a person out of the product because two
  attribute rules disagree, which is the wrong failure — the slice-3 lesson about never
  failing boot on an identity-store condition, restated at sign-in. The withheld grant is
  recorded as a violation, so it is visible rather than silent.

**Existing violations are reported, never auto-resolved.** A constraint added to a store
where people already hold both sides has violations on day one. The constraints page runs
the violation query over the whole store and lists them; resolving one is an administrator
revoking a grant, with the reason recorded in the history.

**Dynamic separation of duties is already shipped and is named here so the exclusion is
legible.** One acting role per application per tab, with `acting_role` in the audit, is
dynamic SoD: a person holding both `orders.buyer` and `orders.approver` cannot exercise
both in one request, and the audit says which capacity acted. This design adds no dynamic
constraint kind, because activation already enforces the only rule a dynamic constraint
would express.

**Risk analysis and mitigation records — the rest of the SAP GRC shape — stay out.** A
mitigation record is a workflow with an owner, a compensating control and an expiry, and it
belongs with the access-review surface once campaigns exist, not in the constraint table.

## Structural decision 3: eligibility is a row, elevation is a windowed grant

```text
tql_role_eligibility(user_id, role_id, max_minutes, requires_reason, requires_approval,
                     expires_at)
```

An eligibility says a person **may take** a role, and grants nothing. It never reaches the
principal: no query reads it at resolution, so an eligible role is absent from the union,
absent from `roleGrants`, and invisible to every policy.

**Elevating** creates an ordinary `tql_user_roles` row with `source = 'elevation'` and
`ends_at = now + minutes`, capped by `max_minutes`. Everything downstream needs no change,
because a windowed grant is what slice 2 shipped: the resolution contracts already filter
`starts_at`/`ends_at`, `find-role-grants-by-user-id` already carries the window, and expiry
needs no sweeper — the row stops resolving when the window closes.

**The one thing that does need building is the same-session effect.** A principal is frozen
in the session at sign-in, so an elevation would otherwise take effect at next login, which
makes the feature useless for its actual purpose. The elevate action therefore **re-resolves
the principal and replaces it in the caller's session**. `SessionStore` gains
`replacePrincipal(sessionId, principal)`, implemented over the existing rotate seam in both
stores, so the elevation is live on the caller's very next request.

That re-resolution is scoped deliberately: it re-reads *this* caller's own principal in
*this* caller's own session. It is not a general mid-session refresh, which would change the
frozen-principal contract every other surface relies on. Other sessions of the same person
see the elevation at their next sign-in, and the design says so rather than implying
otherwise.

**Approval-gated elevation is the request surface, not a second mechanism.** Entra PIM
splits eligibility into "activate freely" and "activate with approval". The second is
exactly structural decision 6's request with a role owner as approver, so an eligibility
requiring approval routes through the request store and lands the same windowed grant.

**Recording.** An elevation writes a history row with `source = 'elevation'`, the reason
when one is required, and the window. Its `correlation` is the elevation's own id, so the
expiring revoke — recorded when a review or an admin ends it early — links back.

## Structural decision 4: groups become writable, and SCIM Groups can point at the managed store

Three things land together, because each is useless alone.

**Write contracts.** `create-group`, `delete-group`, `add-group-member`,
`remove-group-member`, `grant-group-role`, `revoke-group-role`, plus the reads
`list-groups`, `list-group-members`, `list-group-roles`. All are `roleManagement` contracts
except the two membership writes, which are `userManagement` — membership is a fact about a
person, and delegating "who is in the sales group" is not delegating "what the sales group
may do".

**Membership windows.** `tql_user_groups` gains `starts_at`/`ends_at`, and
`find-groups-by-user-id`, `find-roles-by-user-id` and `find-permissions-by-user-id` gain
the same window filter their sibling arms already carry. The deferral said group membership
windows would be decided here; they are, and they are the same window as everywhere else,
because a second time model would be a second thing to explain.

**A managed-store SCIM Group contract set.** `tesseraql.scim.groups.enabled` on a managed
realm builds its `ScimGroupContract` from bundled SQL against `tql_groups`/`tql_user_groups`
instead of requiring nine files. The nine config keys stay, and a deployment that sets them
keeps its own SQL — this adds a default, it does not remove a seam.

**Deferred to its own slice (4b), for a measured reason.** `ScimGroupService.create` runs
`createSql` through `executeQuery` and reads the assigned id from the returned row, so a
bundled contract set would need `insert … returning` — which MySQL and SQL Server do not
have. Making it portable means changing the create seam itself: mint the id before the
insert, execute the insert as an update, then re-read. That is a change to a shipped
provisioning contract, with every existing deployment's nine hand-authored files depending
on the current shape, so it earns its own slice rather than riding the store work.

**The admin surface** gets a groups page, a group detail page with members and roles, and
membership editing from the user detail page.

## Structural decision 5: access review is a campaign over a snapshot

```text
tql_access_reviews(review_id, review_name, application, opened_at, opened_by,
                   closed_at, closed_by, status)
tql_access_review_items(review_id, item_no, user_id, item_kind, subject_code,
                        source, decision, decided_by, decided_at, note)
```

Opening a campaign **snapshots** the grants matching its scope into items, each `pending`.
A reviewer decides `keep` or `revoke` per item. Closing the campaign executes every
`revoke` decision through `RoleAdmin`, so each revocation passes the same validation, writes
the same history row (`source = 'review'`, `correlation` = the review id), and appears in
the trail like any other change.

**The snapshot is the point.** A campaign that reads live grants asks reviewers about a
moving target and cannot answer "what did we certify in Q3". A campaign that snapshots
answers both, and the gap between snapshot and close is visible: an item whose grant is
already gone at close is closed as `stale` rather than re-revoked.

**Scope is one application or the whole store**, reusing the member list slice 1 already
derives. Per-reviewer assignment — one campaign split across role owners — arrives with
structural decision 6's owner table, since that is where an owner is recorded.

## Structural decision 6: a request is a row, and its approver is the role's owner

```text
tql_role_owners(role_id, owner_kind, owner_ref)
tql_access_requests(request_id, requested_at, requester_id, role_code, reason,
                    requested_minutes, status, decided_by, decided_at, decision_note,
                    granted_until)
```

`owner_kind` is `user` or `group`; a role with no owner is not requestable, which is the
deny-by-default answer to "who approves this" rather than falling back to store-wide
administrators.

A requester asks for a role from the account surface, naming a reason and optionally a
duration. An owner sees pending requests, approves or rejects. An approval lands the grant
through `RoleAdmin` with `source = 'request'`, time-boxed by `requested_minutes` when given.

**Why this is not a workflow document.** The deferred entry proposed composing the approval
workflow engine with the identity write contracts. Measurement makes the cost of that plain:
a transition's command is app-authored 2-way SQL over an app-owned table, and the engine has
no seam for calling a framework service. Landing a grant from a transition command would
mean writing `tql_user_roles` directly — bypassing the SoD check, the history row, the
delegation containment and the window validation that this very campaign adds. The
composition would defeat the governance it was meant to carry.

So requests are framework store rows with a framework write path, and the engine stays what
it is: the way an *application* moves *its* documents. The two are named in each other's
documentation so the distinction is legible, exactly as approval workflow and route
governance already are.

**Requests and eligibility are one mechanism seen twice.** An eligibility with
`requires_approval` creates a request on elevate; a request for a role with no eligibility
is an ordinary access request. Both land a windowed grant through the same call.

## Structural decision 7: delegated administration is a scoped atom with containment

The atom is `tql.iam.admin.app.<name>`, and its wildcard `tql.iam.admin.app.*` — the
`tql.<family>.<verb>.<name|*>` grammar, unchanged. It arrives with its surface: slice 1's
per-application page becomes writable for the applications the caller may administer, which
is the no-new-atoms-without-a-surface rule honoured rather than restated.

**Containment is the whole design**, and it is enforced in `RoleAdmin`, not in the page:

- A delegated admin may write only rows whose role's `application` equals a name they hold
  the atom for. A stack-wide role (`application` null) is never theirs.
- A delegated admin may grant only permission codes prefixed with that application's name,
  and never a code under the `tql.` mark. Delegating administration of `orders` must not
  become a path to granting `tql.app.deploy.*`.
- A delegated admin may not create, edit or delete assignment rules, SoD constraints or
  eligibilities. Those are store-wide instruments; scoping them is a separate design with
  its own containment questions.

`RoleAdmin`'s writes take a `Scope` describing what the caller may touch — store-wide for
`tql.iam.admin.write`, application-limited for the delegated atom. A refusal is
TQL-IAM-4036, naming the application and the code, so the message says why rather than
just no. (4035 went to slice 3's elevation refusal, which shipped first.)

## Structural decision 8: context conditions are two layers, and only one of them is per-request

**Layer A — the deployment allow-list, checked at authentication.**
`tesseraql.security.network.allow` is a CIDR list; a sign-in from outside it is refused with
TQL-SEC-4149 before a session exists. This is the kintone-style stack-wide restriction, and
it is the layer that actually holds, because it runs where the connection is.

**Layer B — grant-level conditions, evaluated per request.**

```text
tql_role_conditions(role_id, condition_kind, value)
```

`condition_kind` is `network` (a CIDR block) or `hours` (a local-time range with days).
Conditions ride the grant into `Principal.RoleGrant`, which is where the deferred entry said
they would ride, and are evaluated against **this request's** context — the frozen principal
carries the conditions, the request carries the context.

The evaluation is a new compiler-emitted step, `tesseraql-auth:conditions`, placed after
`fence` and before `activate`. A grant whose conditions fail is dropped from the active
view: its role leaves `roles`, and its permissions leave `permissions` unless another
surviving grant or a direct grant delivers them. This is `Activation`'s existing math with
a different filter, so it composes with activation rather than competing with it.

**It narrows and never widens**, like activation, so a spoofed address can only take
capability away. That is what makes the honest limit tolerable: the address is whatever the
edge presented, `SessionStore.ClientInfo` already says so, and layer A is where a deployment
gets an enforceable answer. The design says this plainly rather than implying that layer B
is a security boundary.

**The unhosted boot gets conditions too.** Unlike `fence` and `activate`, the conditions
step has no topology guard: a single-application runtime evaluates conditions the same way,
because a role's conditions are a property of the grant, not of the stack.

## Slices

Eight, in dependency order. Slice 1 is the foundation every later slice writes to; slices 4
and 8 are independent of the rest and can move if the order needs changing.

1. **Grant history** — `tql_grant_history`, the contracts, both write paths recording, the
   history page and the per-user history card.
2. **Separation of duties** — the constraint tables, the two checkpoints, the violation
   report, the constraints admin surface. Needs slice 1 for the refusal trail.
3. **Eligibility and elevation** — `tql_role_eligibility`, the elevate action,
   `replacePrincipal`, the self-service elevation card. Needs slice 1.
4. **Groups** — write contracts, membership windows, the admin surface, the managed-store
   SCIM Group contract set. Needs slice 1 for the membership history rows.
5. **Access review** — campaign tables, open/decide/close, revocation through `RoleAdmin`.
   Needs slices 1 and 2 (a close revokes, and a revoke is checked).
6. **Access requests** — role owners, the request store, the requester and approver
   surfaces. Needs slices 1 and 3 (approval lands a windowed grant).
7. **Delegated administration** — the scoped atom, `RoleAdmin.Scope`, the writable
   per-application page. Needs slices 1 through 6, because containment must cover every
   write the campaign added.
8. **Context conditions** — the allow-list at authentication, `tql_role_conditions`, the
   conditions step, the condition editor.

## Guards

- **`FrameworkSurfaceGuardTest`** demands a registry entry for every new framework HTTP
  route; each slice adding a page adds its entry in the same commit.
- **`ErrorCodeUniquenessTest`** refuses a code declared at two sites; TQL-IAM-4034/4035/4036
  and TQL-SEC-4149 are declared once each, at the class that raises them.
- **`GeneratedReferenceTest`** regenerates on any error-message or schema change.
- **A new store guard** asserts that every table this design adds exists in all four dialect
  schema files, so a dialect cannot be forgotten — the trap slice 2 hit three times by hand.
- **A containment test** proves the delegated atom cannot reach a stack-wide role, another
  application's code, or anything under `tql.`.

## Test plan

- **Unit** — the SoD evaluator (block versus warn, existing violations, the withheld rule
  grant), the condition evaluator (inside and outside a CIDR, inside and outside hours, an
  absent condition), the containment scope, the snapshot-versus-live review close.
- **Store integration** (`RoleStoreIntegrationTest`'s sibling, PostgreSQL container) — the
  history row per write path, membership windows filtering resolution, an elevation
  resolving and then expiring, a review close revoking.
- **Stack integration** (`StackActivationIntegrationTest`'s arrangement) — a role denied by
  a network condition disappears from the active view while the union still holds it; an
  elevation taking effect on the next request in the same session; a delegated admin
  refused on another application's role.
- **The revert-the-fix rule** — every regression test in this campaign is proven by
  reverting its change and watching it go red, the method that has repeatedly caught tests
  that did not test what they claimed.

## What moves in the docs

`iam-admin.md` gains the new pages. `authentication.md` gains layer A. `data-scoping.md` is
untouched — none of this changes row authority. `approval-workflow.md` gains the sentence
distinguishing document workflow from access requests, mirroring the paragraph that already
distinguishes it from route governance. `application-roles.md`'s deferred ledger gains a
line per entry pointing here.

## Deliberately not in this design

- **Risk analysis and mitigation records** (the rest of SAP GRC). A mitigation is a
  compensating control with an owner and an expiry, and it belongs with campaigns once
  reviewers exist in the store.
- **Scoping rules, constraints and eligibilities to a delegated admin.** Store-wide
  instruments stay store-wide; each would need its own containment answer.
- **Device posture and step-up authentication as conditions.** Posture stays IdP territory,
  as the original deferral said. Step-up is a session property, not a grant property.
- **Per-record sharing ACLs, deny grants, role hierarchy, per-tenant roles, a permission
  catalogue UI.** Unchanged permanent stances from `application-roles.md`.
- **Mid-session revocation.** Elevation replaces the principal in the elevating caller's own
  session; nothing else refreshes a frozen principal, and the disable-user pattern stays the
  answer for cutting access off immediately.

## Open questions

Each gated on the slice it blocks, with a recommendation.

1. **Does history record reads?** — *gates slice 1.* Recommended: writes only. A grant
   history that also records who looked is two logs in one table, and the route audit
   already records the page views.
2. **Does a `warn` SoD violation block a rule converge?** — *gates slice 2.* Recommended: no
   — `warn` warns at both checkpoints, and the sign-in path has nowhere to show a warning,
   so it records the violation and continues.
3. **May a person elevate into a role they already hold?** — *gates slice 3.* Recommended:
   no, refused as a no-op, since the grant would collide with the held row and the window
   semantics of "extend what I have" are a different feature.
4. **Do group memberships get the `source` column too?** — *gates slice 4.* Recommended:
   yes, for symmetry with both other assignment tables, defaulting to `admin`, so a future
   SCIM-provisioned membership is distinguishable from a hand-made one.
5. **Who may close a review?** — *gates slice 5.* Recommended: the opener or any store-wide
   admin, never a delegated admin, because closing executes revocations across the scope.
6. **Can a requester cancel a pending request?** — *gates slice 6.* Recommended: yes, and it
   records a history row with no grant change, because the request trail is part of the
   record even when nothing was granted.
7. **Does the delegated atom imply `tql.iam.admin.view`?** — *gates slice 7.* Recommended:
   no. The atom grants its own view of its own application; store-wide sight stays a
   store-wide grant.
8. **Are hours evaluated in the server's zone or the deployment's?** — *gates slice 8.*
   Recommended: a configured zone (`tesseraql.security.conditions.zone`), defaulting to the
   JVM's, because "login hours" means the business's hours and a server moving zones must
   not change who may work.
