# Per-application roles

Implementation design for the application role model the stack-shells review set as the next
identity step: roles gain a per-application axis, attribute rules assign them automatically,
individuals can hold permission grants directly, and a person holding roles in several
capacities (兼務) selects the one they act as at use time. One requirement is hard and named
up front because it shapes the whole activation design: **two browser tabs must never mix
acting roles** — the active role is tab-scoped state, not a session attribute.

The model matches mainstream practice deliberately, so each piece can be judged against a
known shape: per-application roles are Entra ID app roles and Keycloak client roles; role
bundles plus per-user direct grants are the Salesforce profile-and-permission-set pair;
selection at use time is NIST RBAC session activation; and rule-based assignment from
department and title attributes is what domestic business packages (部署・役職 →
機能権限の自動付与) have done for decades.

Written 2026-08-18, before implementation, measured against main at #871 — the stack-shells
campaign is complete, so the `tql.<family>.<verb>.<name|*>` atom grammar, the `tql.app.use`
fence, the origin surfaces and the policy-code namespace fence (TQL-YAML-1406) are all
shipped ground this design stands on, not assumptions.

**Status: awaiting review. No slice is implemented.**

## What exists today, measured

**The store has both halves of a two-level model and no application axis anywhere.** Eight
tables in the managed identity pack
(`tesseraql-identity/src/main/resources/io/tesseraql/identity/pack/default/schema/postgres.sql`):
`tql_users` (fixed ten columns — no attributes of any kind), `tql_groups`, `tql_roles`
(`role_id`, `role_code` unique, `role_name` — no `tenant_id`, no application), `tql_permissions`,
and four bare join tables. Role→permission lives in `tql_role_permissions`; **there is no
user→permission table** — both arms of `find-permissions-by-user-id.sql` route through roles.
`docs/iam-admin.md` already promises "granted to a user directly or bundled into whatever
roles your deployment defines" — the doc describes a capability the store does not have; this
design builds it.

**A principal is resolved once, at sign-in, and frozen.** `IdentityService.resolvePrincipal`
(`tesseraql-identity/.../IdentityService.java:107-133`) runs four contract queries and builds
the `Principal` record (subject, loginId, displayName, tenantId, groups, roles, permissions,
claims = the raw user row). `SessionStore` serializes the whole record into
`tql_session.principal_json`; nothing rewrites it until re-login, and `rotate` copies it
verbatim. So "at sign-in" is already the framework's one evaluation moment for authority —
the rule engine below lands exactly there, adding no second moment.

**The role query computes provenance and throws it away.** `find-roles-by-user-id.sql`
selects `'DIRECT' as grant_type` / `'GROUP' as grant_type` and `IdentityService.column()`
reads only `role_code`. The narrowest existing seam between "all roles" and a structured
view is already half-built.

**Everything that reads `principal.roles` or `principal.permissions`, exhaustively** —
this is the list whose semantics the active view (structural decision 4) must answer for:
route policies (`Policy.Rule.matches`, exact string containment), scope arms
(`CompiledScopeResolver`, reusing `Policy.Rule` for `when: {role|permission|claim}`),
menu filtering (`MenuSpec.visibleFor` via `ShellChrome.menu`), field write policies
(`InputField.policy` through `RequestBinder.permitsWrite`), ambient SQL binds
(`AmbientBinds` — the closed six-field set), bearer minting (`SessionTokens.issue` mints
`roles`/`permissions` claims from the ambient map), the `tql.app.use` fence and every atom
check (`Atoms`, `OpsScope` — permissions only), and Studio/Copilot's role gates (the one
roles-reading framework surface, already scheduled to retire with the Studio shell).

**One coupling is load-bearing: permissions are derived from all roles, with no per-role
attribution.** The permission query unions role and group arms; the frozen principal holds
two flat lists. Filtering `roles` to an active subset would leave `permissions` computed
from the full set — any activation design must carry role→permission attribution into the
principal, or re-query per request. This is why structural decision 4 changes the
`Principal` record rather than adding a filter.

**Users carry no attributes, and every federation path discards them.** `tql_users` has no
department/title/anything column and no attribute table. SCIM persists exactly seven fields
(`ScimUser` is `@JsonIgnoreProperties(ignoreUnknown = true)` — the enterprise-extension
attributes `department`, `division`, `costCenter`, `manager`, `employeeNumber` are parsed
away); the SAML and OIDC linkers bind four fields on first login and **return early when the
user already exists**, so nothing is ever re-synced. `Principal.claims` is the raw user row,
so a `sql` realm can already widen `find-user-by-login` to surface extra columns — the only
attribute extension point today, per-realm and undeclared.

**IAM Admin displays and never edits authority.** Its nine routes cover users, sessions and
delegations; roles, groups and permissions on the user detail page are display-only, and
`docs/iam-admin.md` states it as deliberate. `RealmConfig.Capabilities` declares
`roleManagement` (`readWrite|readOnly|none`) and **nothing anywhere checks it** — only
`userWriteAllowed()` is enforced (`IdentityService.executeUpdate:80-84`, TQL-IAM-4030). The
capability vocabulary this design needs already exists, unwired.

**The write contracts exist, managed-pack only.** `ENSURE_ROLE`, `ASSIGN_USER_ROLE`,
`ENSURE_PERMISSION`, `ASSIGN_ROLE_PERMISSION` (`IdentityContracts.java:25-32`) are
bootstrap-only today, idempotent, with dialect variants — the seeding path IAM Admin's
write surface and the reconciler below extend rather than invent.

**A rule engine with computable overlap already ships.** Decision tables
(`tesseraql-core/.../decision/DecisionTables.java`) deliberately restrict cells to
comparisons — `eq`, `between`, `in`, `bool`, `subtree` — "so overlap between rows stays
computable", and the `subtree` kind already answers org-hierarchy membership against
`tql_org_closure` (the Phase 29 org-unit foundation, `managed` mode). The assignment rules
below copy this restriction and the subtree kind, not the engine itself (a decision table is
an app-declared value producer; an assignment rule is store data — different owner, same
discipline).

**Activation's transport already has every seam it needs, shipped.** The base-path campaign's
rule — *a URL is base-relative inside the runtime and acquires the prefix at the moment it
becomes a wire URL* (`docs/base-path.md` §7) — has exactly two chokepoints: the template link
builder (the `base` model value, `HtmlResponseRenderer.java:228-230`) and
`RedirectRenderer.negotiate` for `Location` headers. The relay resolves every request against
the member's declared prefix on a segment boundary and re-resolves live state per request;
the gateway already owns an ingress header-strip step (#826). The fence precedent
(`RouteCompiler.java:1821` emits `tesseraql-auth:fence` after every authenticate step; the
producer no-ops unless `STACK_MEMBER_BEAN` is bound) is the exact placement pattern the
activation step reuses. And `?slot=canary` in the ops shell already threads a per-request
selection through links and hidden form fields — at console scale; the address-borne design
below exists because that threading does not scale to a whole application's URL surface.

**The audit trail records who, not as-what.** `tql_route_audit` (opt-in per app) records
`actor` = loginId-else-subject and `tenant_id`; no role reaches any audit row
(`RouteAudit.java:75-77`). The 兼務 requirement's second half — "the trail shows which
capacity acted" — needs a column.

**Found defects, recorded here and fixed in the docs sweep:** `docs/approval-workflow.md:403`
documents a guard `principal.role == 'approver'` — `Principal` has no `role()` accessor, so
the path resolves to null and the comparison can never be true; `docs/decision-tables.md:127`
documents `dept: principal.orgUnit` — same, the working spelling is
`principal.claim.org_unit`. Both are doc drift against `EvaluationContext`'s
accessor-name resolution, worth fixing before this design teaches new `principal.*` paths.

## Structural decision 1: roles gain the application axis, and applications declare their own

**`tql_roles` gains one nullable column: `application`.** Null means what every existing row
means — a stack-wide role, the deployment's vocabulary (a department, a team, a duty),
always active, never parsed by the framework. Non-null names a stack member: the role
belongs to that application, appears in its role picker and its per-application admin view,
and is the unit of activation. **The column is the classifier — the framework never derives
meaning from a role string.** This keeps the stack-shells rule intact: framework surfaces
still check atoms, never roles; the activation machinery reads the column and treats the
code as opaque.

**An application declares its own roles, in its own configuration, bundling only its own
codes.** Beside `tesseraql.security.policies`:

```yaml
tesseraql:
  security:
    roles:
      - code: orders.approver
        name: 承認者
        permissions: [orders.approve, orders.read]
      - code: orders.viewer
        name: 参照
        permissions: [orders.read]
```

The declaration is the application author's statement of its duty shapes — reviewable,
promotable, lintable, exactly what Decision 26 (declarations never share) permits: a role
declaration references only the declaring application's own permission codes, which the
shipped TQL-YAML-1406 fence already validates for policies and now validates here too. The
role code carries the application's own name as its first segment — `orders.approver`, not
`approver` — the same explicit-not-auto-prefixed rule the permission codes got, for the same
reason: the store must show strings the author wrote. Application names are dot-free
(TQL-YAML-1405), so the first dot splits code from suffix unambiguously — but that parse is
a lint convenience, never a runtime mechanism; the `application` column stays authoritative.
**Lint and boot: TQL-YAML-1407** — an application role whose code does not start with the
application's own name, whose permission list strays outside the application's codes, or
that collides with a `tql.*` atom is refused at lint and at boot.

**The runtime reconciles declared roles into the store at boot, managed realm only.** The
same idempotent upsert path the seeder uses (`ensure-role` + `assign-role-permission`),
extended with the `application` column: declared roles are upserted, their permission
bundles converged (bundle rows the declaration no longer contains are removed — the
declaration owns its bundle), and **every application role receives its application's
`tql.app.use.<name>` atom by construction** — holding a duty in an application implies
being allowed through its fence, and the implication is a visible `tql_role_permissions`
row, not a runtime special case. A role removed from the declaration entirely is *not*
deleted (it may hold assignments); it is reported as orphaned on the per-application admin
view — open question 5. A `sql` realm gets no reconciliation: the deployment owns that
store, and the runtime refuses the write path the same way IAM writes are refused today
(TQL-IAM-4030's rule, applied via the until-now-unenforced `roleManagement` capability —
structural decision 6).

**The deployment can also create application roles in IAM Admin** (same column, same prefix
rule enforced at the write), for the deployment-specific shapes an application author cannot
know. Both kinds are rows in one table; the declaration is just the reviewed way to make
them.

**Rejected: deriving the application from the role-code prefix** (no column) — the campaign's
standing rule is declared-not-derived, and it would make the framework parse role strings,
the exact thing stack-shells forbids framework surfaces to do. **Rejected: a separate
`tql_app_roles` table** — one concept, one table; the null/non-null column *is* the
classification, and every existing query stays valid. **Rejected: composite uniqueness
`(application, role_code)` with unqualified codes** — two applications would both hold an
`approver`, and `Principal.roles` is a flat string list; the moment two same-named roles can
be held at once, every consumer needs qualification anyway, so qualify the code itself.
**Rejected: application roles replacing stack-wide roles** (rejected in the original review
too) — cross-application bundling is real (経理部 spans apps) and is exactly what
stack-wide roles are for; the two kinds coexist with different owners.

## Structural decision 2: a person can hold a permission directly

**New table `tql_user_permissions` (`user_id`, `permission_id`), and a third arm on
`find-permissions-by-user-id.sql`.** The Salesforce shape: roles carry the common bundles,
direct grants carry the exceptions — the one person who also needs `orders.export` this
quarter — without minting a synthetic one-member role per exception, which is what
deployments do today and what turns role lists into noise. Grants are **additive only**;
there is no deny row anywhere in this design (the same stance scope arms and policy rules
already take — absence denies, presence grants, nothing subtracts).

Direct grants apply to framework atoms exactly as to application codes — `tql.app.deploy.orders`
granted to one CD pipeline's principal directly is the model working as intended.

The identity contracts grow read/write pairs (`find-direct-permissions-by-user-id`,
`grant-user-permission`, `revoke-user-permission`), managed-pack implementations shipped,
**optional for `sql` realms**: a realm that does not provide them simply has no direct
grants (the arm contributes nothing), and IAM Admin's grant editor renders read-only for
it. The nine required standard contracts do not grow — an existing `sql` realm keeps
working untouched.

## Structural decision 3: attributes arrive with the user; rules assign the roles

**New table `tql_user_attributes` (`user_id`, `name`, `value`, PK user+name).** Free-form
named string attributes — 部署, 役職, 雇用区分, whatever the deployment's rules need —
merged into `Principal.claims` at resolution (user-row columns win on collision, so nothing
shipped changes meaning). Three writers, one shape:

- **IAM Admin edits them** on the user detail page (managed realm, write-gated).
- **SCIM stops discarding them**: `ScimUser` gains the enterprise-extension fields
  (`department`, `division`, `costCenter`, `employeeNumber`, `manager.value`) plus a
  configured map of additional SCIM paths → attribute names, upserted on create *and*
  update — provisioning is the natural attribute source in every deployment that has it.
- **SAML/OIDC linkers gain a configured attribute map** (assertion attribute / ID-token
  claim → attribute name) and **stop returning early on existing users**: the mapped set
  re-syncs at every login, fixing the measured never-re-synced gap. Unmapped claims stay
  discarded — capture is declared, not promiscuous.

**Assignment rules are store data, managed in IAM Admin, evaluated at sign-in.** A rule
grants one role when its conditions all match the user's attributes:

- `tql_role_rules` (`rule_id`, `role_id`, `enabled`)
- `tql_role_rule_conditions` (`rule_id`, `attribute_name`, `match_kind`, `value`) — kinds
  `eq`, `in` (rows sharing a rule and attribute OR together; distinct attributes AND),
  and `subtree` (the attribute holds an org-unit id; matches descendants via
  `tql_org_closure`, available when the org-unit foundation runs `managed` — refused at
  rule-save otherwise, TQL-IAM-4032 on a malformed or unsatisfiable rule).

The condition vocabulary is deliberately the decision-table discipline — comparisons, never
expressions — so rules stay enumerable, their overlap stays computable, and the admin page
can answer "which rules produce this role" by reading, not evaluating.

**Evaluation materializes, per user, inside the sign-in resolution.** `resolvePrincipal`
gains a step before the role query: evaluate the enabled rules against this user's
attributes, then converge this user's `tql_user_roles` rows *of rule provenance* — insert
the missing, delete the no-longer-produced, never touch a manually assigned row. That needs
provenance: **`tql_user_roles` gains a `source` column** (`admin` | `rule`; existing rows
backfill `admin`), finally using the seam the discarded `grant_type` column pointed at. A
manual assignment always survives recompute; a rule assignment survives only while a rule
produces it.

Materializing (rather than computing roles on the fly) keeps `find-roles-by-user-id` and
`find-permissions-by-user-id` as the single truth, keeps IAM Admin's "who holds this role"
a query rather than an evaluation over all users, and makes assignments attestable. The
cost — a write inside sign-in — is bounded: rules are per-user, the converge is a diff of
two small sets, and the login transaction already writes (throttle counters, session row).
IAM Admin gains **recompute now** (one user / all users) for the admin who edited a rule
and wants the store to show the result before each user's next login; live sessions are
frozen either way, exactly as they are for every grant change today.

**Rejected: evaluating rules at request time** — the framework has one authority moment
(sign-in) and this design keeps it; per-request evaluation would be the first per-request
identity query in the codebase. **Rejected: a standing reconcile job** — a batch sweeping
all users continuously is machinery with its own failure modes, solving only the
"rule edited, user not yet re-signed-in" window that recompute-now already covers on
demand. **Rejected: declaring assignment rules in application YAML** — a rule references
deployment vocabulary (departments, titles) an application cannot know, and mentions roles
across applications; it is deployment data, like the assignments it produces.
**Rejected: expressions in conditions** — the decision-table restriction exists for a
reason; a rule you cannot enumerate is a rule you cannot audit.

## Structural decision 4: activation — one acting role per application, chosen at use time

**The line, drawn once:** *reachability reads the union; conduct reads the active view.*

- **The union** — everything the principal holds — answers the `tql.app.use` fence, every
  framework atom check (ops shell, deploy endpoint, IAM Admin, portal tiles), and bearer
  minting. Operators and framework surfaces do not activate; a grant's reachability is a
  property of the principal, not of a tab.
- **The active view** answers everything *inside* an application: route policies, scope
  arms, menu filtering, field write policies, ambient `principal.*` binds. The active view
  inside member M is: **all stack-wide roles, plus the one activated M-role (if any), minus
  every other application-scoped role** — and permissions recomputed to match: the active
  roles' bundles plus the principal's direct grants.

**The `Principal` record carries the attribution the recompute needs.** Two new components,
populated at sign-in by one widened query each: `roleGrants` — a list of
`RoleGrant(role, application, permissions)` rows (application null for stack-wide) — and
`directPermissions`. `roles()` and `permissions()` keep returning the full union, so every
existing consumer, serialization included, is untouched; a pre-upgrade `principal_json`
deserializes with empty grants and behaves exactly as today (no application roles existed
before this design, so the union *is* the active view for such sessions). Bearer, API-key
and mTLS principals carry empty `roleGrants` — claim-asserted roles have no application
axis and are always active, stated plainly: activation is a property of store-resolved
principals.

**Activation is a per-request swap, in the compiled security chain.** A new
`tesseraql-auth:activate` step is emitted directly after the fence (same placement pattern,
same `STACK_MEMBER_BEAN` no-op guard — unhosted boots have no activation, like they have no
fence). It reads the acting-role signal (structural decision 5), validates it against
`roleGrants` — **the signal can only select among roles the caller genuinely holds for this
member, so a forged signal can narrow, never widen** — and replaces the exchange's principal
property with the derived active-view `Principal`. Everything downstream — authorize, the
binder, scopes, menus, templates, audit — reads the swapped principal and needs no change
at all. Order: authenticate → fence (union) → activate (narrow) → csrf → authorize (active).

**Entry, when no role is activated yet:** if the caller holds no roles scoped to this
member, nothing happens — an application with no application roles never sees any of this
machinery, which is the compatibility default. If the caller holds exactly one, a browser
HTML GET is 302-redirected to the same path with that role activated (choice of one is no
choice). If the caller holds several — the 兼務 case — a browser HTML GET is redirected to
the **role picker**; a non-HTML request (API callers, fetch) is *not* redirected and runs
with no application role active: deterministic, and safe because absence denies. A bearer
caller that wants an active role states it in the address like everyone else; with empty
`roleGrants` the refusal is TQL-SEC-4148.

**The picker and the switcher.** The picker is an origin surface
(`/_tesseraql/roles?app=<member>&redirect=<wire path>`, `LoginRedirects` sanitization) —
the origin holds the session and the direction of travel is surfaces-at-origin; it lists
the caller's roles for that member by `role_name` and 302s into the chosen role's address.
The switcher (所属切替) is shell chrome on the member page — the ops-shell member-switcher
shape — rendering the caller's *other* roles for this member as direct links that swap the
activation segment in place; opening one in a new tab is precisely how two capacities run
side by side.

**Rejected: filtering `roles` without recomputing `permissions`** — the measured coupling
makes that incoherent (policies checking permissions would answer for roles the view just
removed). **Rejected: activation as session state** — violates the hard requirement by
construction; every tab shares the session. **Rejected: activating subsets or multiple
concurrent roles per application** — the requirement is selection, the motivation is
separation of duties and an unambiguous audit sentence ("acted as 承認者"); one at a time,
per application, per tab. **Rejected: applying the active view to framework atom checks** —
an operator's reach must not depend on which tab they used to open the console; the
backlog's own line (fence stays union-based) generalized.

## Structural decision 5: the acting role rides the address

The hard requirement — tabs never mix — admits exactly one mechanism that works for a
server-rendered, works-without-JS framework: **the acting role is part of the URL.** A tab
*is* its URL; state carried there is tab-scoped by construction, bookmarkable, shareable in
a bug report, visible in an access log, and survives reload — every property a session
attribute lacks and a JS-managed header cannot give plain HTML navigation.

**The address form: `/<member>/_as/<role>/…`** — a framework-reserved second segment under
the member prefix, in the `_tesseraql` philosophy (the framework marks its own space; no
application route may begin with `_`, so the segment is collision-free by the shipped name
rules). The role code is URL-encoded (non-ASCII codes are legal and stay legal).

**The relay normalizes it; the member trusts the relay.** `StackRelay` already matches the
member prefix on a segment boundary; it now also recognizes the `_as` segment, strips it
from the path it forwards, and hands the role to the member as an internal header
(`Tesseraql-Acting-Role`). The gateway's ingress strip removes that header from every
outside request unconditionally — it is relay-minted, like the strip set, never
client-supplied. The member's activate step reads the header and validates against
`roleGrants` — a direct-to-internal-port caller forging the header can still only select
among their own held roles (the ops-shell forged-call stance: the member re-checks, the
transport adds reach, never authority). Route matching inside the member is untouched — the
member never sees the segment.

**Outbound, the segment rides the existing base-path chokepoints.** The rule stays *a URL
is base-relative inside the runtime and acquires the prefix on its way out*; under an
active role the effective prefix for this request is `basePath + "/_as/" + role`. The two
shipped chokepoints — the `base` model value the templates resolve against, and
`RedirectRenderer.negotiate` for `Location` headers — are exactly where it lands; relative
htmx/fetch URLs resolve against the document and follow for free. One carve-out: targets
under `assets/` skip the segment (an asset is role-independent; keying the browser cache by
role would duplicate it) — `assets` is already a reserved word, so the carve-out parses on
shipped grammar. A 302 into a *different* activation (picker, switcher) is by definition
not base-relative and is built explicitly.

**Refusals:** an `_as` role the caller does not hold for this member — revoked since
bookmarking, someone else's shared link, a typo — answers **TQL-SEC-4148**: for a browser
HTML GET, a redirect to the picker (the human fix is "choose again"); otherwise 403. The
code is minted here because the shipped SEC family has no "authenticated, allowed in, wrong
capacity" answer; 4147 stays unminted (a prior slice deliberately skipped it — this design
does not reuse the number).

**Rejected: a query parameter threaded through links** (`?slot=canary`'s shape) — it works
at console scale because the console owns its dozen links; an application's whole URL
surface would have to thread it by hand, and one missed link silently drops the role — the
address-prefix design makes the carry structural instead. **Rejected: sessionStorage plus a
header** — per-tab, but only JS can send it; every plain `<a>` and form post loses the
role. **Rejected: a per-tab cookie** — cookies are origin-scoped; there is no such thing.
**Rejected: `window.name` or client-side routing** — the framework's grain is
server-rendered declarative pages. **Rejected: the member parsing `_as` itself with no
relay involvement** — it would work (and the activate step still validates), but the relay
is the one place already parsing member addresses per request, and normalizing there keeps
member route matching byte-identical to today; recorded because the unhosted boot skipping
activation follows from it.

## Structural decision 6: the admin surface answers per application

IAM Admin grows from user administration to grant administration, in the model's own
vocabulary — and its first slice needs no schema at all:

- **Per-application grant views** (`/_tesseraql/admin/applications`, one page per member):
  who may use it (`tql.app.use.<name>` holders), its application roles and their members,
  its declared permission codes and who holds them by which path, the ops/deploy/studio
  atom holders. Every row is derived from the shipped atom grammar plus the existing
  store — zero new declarations, which is why it ships first.
- **Role management**: create/edit application roles (prefix rule enforced at the write),
  edit stack-wide roles, assign/unassign users (writing `source = admin`), see rule-derived
  membership labelled as such, and the orphaned-declared-roles report.
- **Direct grants**: the user detail page's permission section becomes an editor.
- **Attributes**: shown and editable on the user detail page.
- **Rules**: list/create/edit/disable per role; each rule shows its conditions and a
  "recompute now" action (one user from their detail page, all users from the rule page).
- **Capabilities finally enforced**: every write above is gated by the realm's
  `roleManagement` capability — declared in `RealmConfig` since Phase 25 and never checked;
  a `sql` realm defaults to read-only and refuses with **TQL-IAM-4031** naming the
  capability, the `userWriteAllowed` precedent applied to the rest of the vocabulary.

All pages stay behind `tql.iam.admin.view` / `tql.iam.admin.write` — the store has no
application axis for *administering* (an identity admin administers the store; per-app
admin delegation is deliberately not in this design).

## Audit: the acting role is recorded

`tql_route_audit` gains a nullable `acting_role` column (V2 migration, all three dialects);
`RouteAuditSink.record` gains the field; `RouteAudit` reads it from the swapped principal's
activation (null when nothing is activated — unchanged rows for every request outside the
model). The audit sentence the requirement asked for — *who acted, as which capacity* — is
then structural, not inferred. Workflow history and ops actor recording keep recording
identity only: a delegate already "acts as themselves" by that design's own rule, and ops
actions are union-scope surfaces where no activation exists.

## Slices

Five PRs, each independently green and observable; 1 ships on today's store, 3–5 all stand
on 2, and 5 does not need 3 or 4:

| # | Slice | Contents | End state |
| --- | --- | --- | --- |
| 1 | The per-application grant views | Read-only IAM Admin pages derived from the atom grammar + existing store; zero schema | An admin answers "who may do what in this application" from one page |
| 2 | The store axis and direct grants | `tql_roles.application`, `tql_user_permissions`, `tql_user_roles.source`, `Principal.roleGrants`/`directPermissions`, widened contracts (optional for `sql` realms), IAM role/grant editors, `roleManagement` capability enforced (TQL-IAM-4031) | Roles belong to applications; exceptions are grants, not synthetic roles |
| 3 | Declared application roles | `tesseraql.security.roles` + TQL-YAML-1407 (lint + boot), boot reconciliation with the implicit `tql.app.use` atom, orphan report | An application ships its duty shapes; the deployment assigns people |
| 4 | Attributes and assignment rules | `tql_user_attributes` + IAM editing, SCIM enterprise capture + update-path upsert, SAML/OIDC attribute maps + re-sync on login, `tql_role_rules`/`_conditions` (TQL-IAM-4032), sign-in materialization with provenance, rules UI + recompute | 部署/役職 arrive with the user; the store assigns roles by rule |
| 5 | Activation | The `_as` address + relay normalization + internal header + ingress strip, `tesseraql-auth:activate` + active-view principal, picker + switcher chrome, TQL-SEC-4148, `acting_role` audit column | A 兼務 user acts as one role per tab, and the trail says which |

## Guards

- **The framework never interprets a role string.** The `application` column classifies;
  the code prefix is declaration-time hygiene, checked at lint/boot/admin-write and read
  by nothing at request time.
- **Activation narrows, never widens**: the active view's roles and permissions are subsets
  of the union, whatever the signal says — pinned by a test that forges the header and the
  segment.
- **Reachability reads the union; conduct reads the active view.** The fence, every atom
  check and bearer minting are union surfaces; policies, scopes, menus, field policies and
  ambient binds are active-view surfaces. Pinned per surface as slices land.
- **The active role rides the address and nothing else** — no session field, no cookie.
  Pinned by the headline IT: two tabs, two roles, interleaved requests, zero mixing.
- **Grants are additive everywhere**: no deny rows in roles, rules, or direct grants;
  absence denies.
- **Recompute owns only its provenance**: rule materialization never inserts, deletes or
  updates a `source = admin` row.
- **A pre-upgrade session keeps its meaning**: empty `roleGrants` ⇒ active view = union.
- **`sql` realms degrade, never break**: every new contract is optional; absent means the
  feature is absent and the admin surface says so read-only.

## Test plan

**Slice 1** — the views render from a seeded store; a member with no grants renders empty
(deny-by-default look); `tql.ops.view.*` holders listed under the wildcard row.

**Slice 2** — store round trip (application column, direct grant reaches
`principal.permissions` and a route policy); provenance backfill; capability refusal for a
`sql` realm (TQL-IAM-4031) and the read-only rendering; pre-upgrade `principal_json`
deserializes.

**Slice 3** — a declared role reconciles (row, bundle, implicit `tql.app.use` atom);
re-declaration converges the bundle; removal orphans-and-reports; lint/boot refusals:
unprefixed code, foreign permission code, `tql.*` collision (TQL-YAML-1407); `sql` realm
refuses reconciliation.

**Slice 4** — SCIM create *and update* land enterprise attributes; SAML/OIDC re-sync
changes an attribute on second login; a rule assigns at sign-in; attribute change flips it
at next sign-in; manual assignment survives recompute; `in` and `subtree` kinds; rule-save
refusal without managed org units; recompute-all converges a cold store.

**Slice 5, the headline IT** (the `StackIdentityIntegrationTest` arrangement): one 兼務
user, two roles in one member — two "tabs" (two paths under different `_as` segments, one
session cookie), interleaved requests: each answers with its own role's policies, scopes
and menu; audit rows carry each `acting_role`; the fence passes on the union both sides.
Plus: single-role auto-302; multi-role picker redirect for HTML, no-redirect stack-only
view for JSON; forged `_as`/header with an unheld role → TQL-SEC-4148 (403 / picker);
emitted links and `Location` headers carry the segment, asset URLs do not; a bearer with
claim roles and no `roleGrants` refused activation; unhosted boot serves no activation
step; switcher swaps in place, new tab keeps the old role — the zero-mixing pin.

## What moves in the docs, and when

With the code PRs, not before: `authentication.md` (roles/realm section gains the
application axis, declared roles, direct grants and the activation model),
`iam-admin.md` (its "does not do" list shrinks — roles/grants/attributes/rules become
its pages; the direct-grant sentence becomes true), `data-scoping.md` (scope arms read the
active view — one paragraph), `account.md` (the switcher), `deployment.md` (seed examples
gain an application-role bundle), `glossary.md` (application role, acting role,
activation), `scim.md`/`saml.md`/`oidc.md` (attribute capture and re-sync),
`reference-error-codes.md` regeneration (1407, 4031, 4032, 4148), `reference-yaml-surface`
(the `roles:` block), the found-defect fixes (`approval-workflow.md:403`,
`decision-tables.md:127`), `CHANGELOG.md` per slice, and this document's status block per
slice. Pre-1.0: each breaking change (the widened `RouteAuditSink` record, the
`ScimUser` shape, the linker re-sync behavior) gets a changelog line and no migration
steps.

## Deliberately not in this design

- **Per-tenant roles.** `tql_roles` stays tenant-less; tenant entitlement stays the
  catalogue's axis (the stack-shells stance, unchanged).
- **Deny/negative grants and role hierarchy.** Additive flat bundles only; a hierarchy is
  a UI convenience this store does not need yet.
- **Separation-of-duties constraints** (mutually exclusive roles, approval-vs-entry
  conflicts). Activation is the *substrate* SoD needs — the audit sentence exists now —
  but constraint enforcement is its own design.
- **Per-application admin delegation** (letting `orders` staff administer `orders` roles).
  The store-wide `tql.iam.admin.*` pair stays; the per-app *views* land, the per-app
  *write* authority does not.
- **A permission registry / catalogue UI.** Stack-shells' standing exclusion.
- **Workflow assignee resolution by role.** Assignees stay app-authored SQL and groups;
  the org-unit seam stays where Phase 28 left it.
- **Attribute governance** beyond capture (validation vocabularies, HR-system pipelines);
  the attribute table is the landing zone, not the pipeline.
- **Activating framework surfaces.** Operators, deployers and identity admins act on the
  union, always.

## Open questions

Each gated on the slice it blocks, with a recommendation:

1. **Does slice 1 ship immediately, before the rest is reviewed?** — *gates sequencing
   only.* Recommended: yes; it is read-only, zero-schema, derived entirely from shipped
   grammar, and useful the day it lands.
2. **The address form** — *gates slice 5.* `_as/<role>` as designed, versus a shorter
   `~<role>` single segment (less to type, but a new grammar mark) versus query-parameter
   threading (rejected above). Recommended: `_as/<role>` — it reads as what it is, parses
   on shipped rules, and reserves nothing new.
3. **Activation granularity** — *gates slice 5.* Exactly one acting role per member per
   tab, stack roles always active, versus NIST-style arbitrary subset activation.
   Recommended: one — the requirement is selection, and the audit sentence needs a
   singular.
4. **Non-HTML requests with several held roles** — *gates slice 5.* Run with no
   application role active (as designed) versus auto-activating one deterministically.
   Recommended: none active — auto-picking a capacity for an API caller invents an answer
   the caller did not give.
5. **Orphaned declared roles** — *gates slice 3.* Report-only (as designed) versus
   delete-when-unassigned versus refuse-boot. Recommended: report-only; a boot refusal
   over a role somebody forgot to unassign is the wrong failure, and deletion discards
   assignment history the admin may want.
6. **Rule evaluation moment** — *gates slice 4.* Sign-in materialization with provenance
   (as designed) versus pure computation at resolve time versus a standing reconcile job.
   Recommended: materialization — one truth in the join tables, attestable, and the
   recompute action covers the edit-to-login window.
7. **Where the picker lives** — *gates slice 5.* Origin surface (as designed, beside the
   portal) versus a member-mounted page. Recommended: origin — the campaign's whole
   direction is surfaces at the origin, and the picker is a session-scoped choice about a
   member, not a member page.
8. **Does the SAML/OIDC re-sync extend to display name and email?** — *gates slice 4.*
   Today those freeze on first login too (same early return). Recommended: yes for the
   mapped attribute set only when the deployment declares the map — changing shipped
   linker behavior silently is the kind of drift this repo keeps finding; declared map,
   declared re-sync.
