# Framework surfaces at stack scope

Implementation design for what remains of [stack-architecture.md](stack-architecture.md)
Decision 14: the ops console becomes a stack-level shell with an application switcher
(slice 7), the authorization model the stack's surfaces share is decided (open question 4 —
reshaped in review from "a grant vocabulary" into a model that serves all three kinds of
user: business users, developers, operators), and the identity surfaces finish their move to
the origin scope (Decision 24's slice-4 remainder: IAM Admin at the origin, the per-member
`auth-ui`/`account` copies retired). It also designs the authenticated deploy surface that
[runtime-replace.md](runtime-replace.md) deferred here — its open question 5 closed as
"deferred to the grants work", and this is the grants work.

**Studio (slice 8) is deliberately not designed here.** The stack architecture's own slices
section says why: "slice 8 is a campaign, not a slice" — `StudioService` couples preview,
editing, apply/reload, the scaffolder, the migration author and the test runner to a runtime,
and it is designed separately **on the delegation pattern this document establishes**. What
this document does decide for Studio is its atom (structural decision 1), so the vocabulary
is settled once.

Written 2026-08-18, before implementation. Everything below was measured against main at #865
unless marked otherwise. Structural decision 1 was rewritten in review at the user's
direction: design the ideal model first — business users, developers and operators each
gettable right — and change the application-name grammar if that simplifies it. It does, and
it is changed below.

**Status 2026-08-18: design approved in review.** All seven open questions closed on their
recommendations — the marked atom grammar with dot-free names and `tql` as the one reserved
name (direction set in review twice: the persona-first rework, then the mark replacing the
family-word reservation), the canary's second switcher entry, the unhosted boot's fallback
mounts, no per-member console opt-out, the deploy endpoint before its page, the full
`tql.app.use` fence, and role bundles documented rather than seeded. Review reshaped the
design five times before approval — the `.app`-marker draft, the standalone-topology
correction, the entry-permission retirement, the structural namespace fence, and the `tql.`
mark — each recorded in place. **Slice 1 is shipped** (the atom grammar and the name rules,
`TQL-YAML-1406`'s namespace fence, and the ops shell with its switcher, loopback delegation,
canary entries and per-member degradation); slices 2 and 3 are pending. One measurement
correction from implementation: the console's live pages ride htmx polling, not SSE, so the
delegation carries ordinary requests — the relay's streaming discipline stays proven for the
surfaces that do stream.

## What exists today, measured

**Every framework surface mounts per member, by SPI.** Five `AppSourceProvider`s — `auth-ui`
and `account` (`tesseraql-camel-runtime`), `iam-admin` (`tesseraql-identity`), `ops-console`
(`tesseraql-ops-ui`), `studio` (`tesseraql-studio`) — are discovered by
`ServiceLoader` (`AppSources.java:33-35`) and materialized into each runtime's `work/apps` by
`SystemApps.load` (`SystemApps.java:50-64`); mounted apps run with the main application's
configuration. A stack of five members runs five copies of each surface, each answering under
its member's prefix.

**The surface runtime exists and already refuses these surfaces on purpose.** Decision 24
gave the stack one framework-owned runtime at the origin scope (`MultiAppHost.java:267-284`,
slot `#portal`); it mounts `auth-ui` and `account` through the ordinary SPI, and its own
config disables `studio`, `ops-console`, `iam-admin` and `mcp`
(`tesseraql/apps/portal/config/tesseraql.yml:13-23`) with a comment deferring them to their
own slices. Its `HostContext` carries `stackMembers` — set only on the surface runtime's
context, so no member can see its siblings (`HostContext.java:44-49`) — and
`PortalProviders.register` is the one-place provider registration this design extends
(`TesseraqlRuntime.java:1720-1724`).

**Each kind of user is authorized by a different mechanism today.** A business user reaches
an application if their *tenant* is entitled (the catalogue's `entitledTenants`, checked by
the relay and the portal — no per-principal model exists, and `PortalProviders` says so in a
comment pointing at open question 4). A developer's Studio authority is a *role* allow-list,
global and per-runtime (`StudioAccess.canEdit` reads `principal.roles`, knows nothing about
application names; `tesseraql.studio.readOnly` and `editRoles`). An operator holds
*permissions* in a two-axis model: `ops.batch.view`/`ops.batch.run` open the console
surface-wide, and `ops.app.<name>` narrows rows per application
(`OpsScope.allowedApps` — `served.contains(app) && granted.test(app)`,
`OpsScope.java:44-48`; out-of-scope and unknown indistinguishable by design, TQL-BATCH-4040).
Three personas, three mechanisms — the model below replaces the three with one.

**The two-axis ops model's own premise has already died.** The entry/scope split was
justified when a runtime hosted several applications and its diagnostics (lanes, slow SQL,
the trace ring, alerts) belonged to no single one. One runtime is one application now, so
that data *is* the member's data (`OpsDashboard.java:169-172` keeps it "runtime-wide" behind
the entry permission only — a mounted-apps-era stance); what genuinely belongs to no member
is the shared process (JVM pinning) and the gateway. And two axes cannot express *view
broadly, act narrowly*, because one `ops.app` set scopes both verbs. `servedApps` today even
includes the mounted system apps (`TesseraqlRuntime.java:1370-1378`), not just the member.

**The permission is a free string; no registry exists.** `ops.app.<name>` is compared by
prefix in `OpsScope` and nowhere else; the identity pack's sample rows literally grant
`ops.app.*`; IAM Admin uses a flat `iam.admin.view`/`iam.admin.write` pair. (A found defect
rides along: `IdentitySchemaMojo`'s javadoc example spells `iam:admin:write` with colons
while the iam-admin routes check `iam.admin.write` — an operator following it seeds a
permission nothing matches. Corrected in the docs sweep.)

**An application's name can contain dots — a freedom the model below withdraws.**
`ApplicationName.segmentViolation` fences `/`, a leading `_` or `.`, and the reserved word
`assets` — nothing else; non-ASCII is deliberately legal (TQL-YAML-1405's javadoc). So
`orders.eu` is a valid name today, and any dotted permission grammar that carries a name in a
dotted position is ambiguous against it. Nothing else in the tree *needs* interior dots: the
address is one segment either way, the migration-history guard measures bytes not characters,
and no example or fixture uses a dotted name.

**Aggregation must happen over runtimes, not over databases.** Decision 14's decisive ground
stands: `RingTracer` is an in-memory ring inside each runtime, so no database connection can
serve the trace pages. Decision 15 constrains the shape — delegation is **real HTTP over
loopback even within one JVM**, and `tesseraql-studio`/`-ops-ui`/`-identity` must never
depend on `tesseraql-camel-runtime` (the module boundary already holds; only the two
identity-surface providers live in the runtime module, and those are what Decision 11
extracts). Decision 17 already reserves the addresses: `/_tesseraql/ops` and
`/_tesseraql/studio` at the origin are "application switcher".

**A member's internal port serves its prefixed addresses, live across replaces.** The ready
probe hits `<basePath>/_tesseraql/health/ready` on the member's internal port; the relay
resolves ports per request through the host's live slots (docs/runtime-replace.md), and the
host knows each member's stable and canary slots (`MultiAppHost` operations, #862). A
delegating shell gets its member origins from the same live state.

**The login bounce is per-member.** An unauthenticated browser GET on an `auth: browser`
route 302s to the *member's own* `/_tesseraql/login` (base-relative, prefixed on the way
out — `ErrorResponseRenderer.java:133,168-171`), which works because each member mounts its
own `auth-ui` copy. Decision 24 called the copies "duplicates against one session store —
harmless, and shared by construction", and deferred their removal here.

**What `hosting.md` currently promises moves with this design.** "The operations console is
per application … `ops.app.<name>` is the permission to open an application's console. An
operator running a stack therefore has one console per application rather than one screen
listing every application's jobs" (`hosting.md:287-297`). That paragraph is the shape this
design replaces, and `app-isolation-model.md` Decision 4 ("the ops console is per-app") is
formally reversed by stack-architecture Decision 14 — the reversal is recorded there already;
the prose moves with the code.

## Structural decision 1: one authorization model for three kinds of user

Designed from the personas outward, per the review direction, and then checked against the
code rather than derived from it.

**The model is two levels, kept: permissions are atoms the framework and the applications
define; roles are bundles the deployment composes.** The identity store already has both
with the role→permission join; what it lacks is a single atom grammar and the per-application
axis everywhere it belongs. One rule binds every framework surface: **a framework surface
checks atoms, never roles.** Roles are the deployment's vocabulary (a department, a team, a
duty); atoms are the framework's. Studio's `editRoles` violates this today — a role
allow-list read by a framework surface — and retires with slice 8.

**The atom grammar: the framework's mark, then family, verb and name —
`tql.<family>.<verb>.<name|*>` — and the name can no longer contain dots.**
`tesseraql.app.name` gains one character to its fence: TQL-YAML-1405 also refuses interior
`.` (non-ASCII stays legal; the address, outbox claims and history-table guard are
indifferent). With names dot-free, an atom parses by splitting on dots — `[tql, family,
verb, name]` — with no ambiguity between a name and a name-plus-action. The leading `tql.`
is the same move the URL space makes with `/_tesseraql/` (settled in review, correcting a
draft that reserved the family words instead): **the framework marks its own space rather
than squatting on the user's** — `tql` is already the framework's short mark (the CLI
alias), one word the name grammar reserves once, and every future family lands inside the
mark without touching the name rules again. Store-wide atoms (no application axis) are
exact strings under the same mark.

The atoms, one row per authority, mapped to the personas:

| Atom | Sentence | Persona | Checked by |
| --- | --- | --- | --- |
| `tql.app.use.<name>` | may use this application | business user | the portal's tiles, and the member's fence (below) |
| `tql.ops.view.<name>` | may see this application's operational data | operator | the shell's switcher and pages, re-checked by the member |
| `tql.ops.run.<name>` | may act on it: run/cancel/rerun jobs, redeliver outbox and dead-lettered events, cancel transfers | operator | the member, on every action |
| `tql.app.deploy.<name>` | may deploy this application | operator / CD pipeline | the deploy endpoint, against the package's declared name |
| `tql.studio.edit.<name>` | may edit this application in Studio | developer | slice 8's shell and delegation (reserved here) |
| `tql.iam.admin.view` / `tql.iam.admin.write` | may see / change the identity store | identity admin | IAM Admin (store-wide: the store has no application axis) |

The wildcard is a terminal `*` (`tql.ops.view.*`, `tql.app.deploy.*`). What each persona's setup
looks like, so the model is judged by its sentences:

- **A business user** holds roles their deployment defines — `経理部` bundling
  `tql.app.use.受注管理` and the *application's own* codes (`tesseraql.security.policies`
  vocabulary, free strings the framework never parses, checked by the application's routes
  as today). The portal shows the tiles their grants and tenant allow; an application they
  cannot use refuses them at its fence, not after four clicks.
- **A developer** holds `tql.studio.edit.<name>` for the applications they own — per
  application, which the global `editRoles` never was. `dev`'s bootstrap admin bundles the
  wildcards so the development loop stays frictionless; production seeds narrow bundles.
- **An operator** holds `tql.ops.view.*` and the `tql.ops.run.<name>`/`tql.app.deploy.<name>` set their
  duty actually needs — view broadly, act narrowly, the asymmetry the retired model could
  not express. The on-call reader is not the deploy pen.

**What retires, all pre-1.0 clean breaks:** `ops.batch.view` and `ops.batch.run` (the verbs
move into `tql.ops.view.<name>`/`tql.ops.run.<name>`); the `ops.app.<name>` *string* (its meaning
lives on as `tql.ops.view.<name>`); Studio's `editRoles` (with slice 8); and the interior-dot
freedom in application names. Eleven console route policies, the identity-pack seeds, the
lint and boot name rules and the docs move together, with changelog lines and no migration
steps. Stack-wide vitals (JVM pinning, the gateway's health) render on the shell's overview
for any holder of any `tql.ops.view` grant — they describe the shared substrate the caller's
application runs on; no `.stack.` axis is invented for one consumer.

**`tql.app.use.<name>` is enforced at two points, and the second is the real one.** The portal's
tiles filter by it (beside tenant entitlement — the two axes are different questions: the
catalogue says which *tenants* an application serves, the grant says which *people* use it).
And the member's own security layer refuses an authenticated principal without the grant,
fence-wide, before any route — so reach is a property of the principal, not of knowing a
URL. Routes declaring `auth: none` are untouched (a public page is public); service callers
(JWT, API keys) pass the same check, because a principal is a principal. Deny-by-default is
the recommendation, with its cost stated plainly: adopting stacks must seed `tql.app.use`
grants (or a `tql.app.use.*` baseline role) before their users sign in — open question 6.

**The namespace fence is structural, not advisory — tightened twice in review.** Framework
atoms are the table above and nothing else; the families hold only what the table shows,
and new verbs arrive only with the surface that checks them. The same store also holds the
applications' own policy codes and the deployment's roles. An earlier draft guarded the
boundary with a lint *warning*; the next draft reserved the family words as forbidden
application names; review caught that the second contradicts the URL fence's own
philosophy — the framework marks its own space, it does not enumerate words users may not
have — and the `tql.` mark is that philosophy applied to atoms:

- **`tql` is the one reserved application name.** TQL-YAML-1405's reserved list (`assets`
  today) gains exactly `tql`, once, forever — every framework atom lives under the mark,
  and a future family is a new second segment inside it, never a new reservation. An
  application named `ops` or `studio` stays legal, exactly as `/ops/...` is a legal
  application address beside `/_tesseraql/...`.
- **An application's own permission codes must begin with its own name** — `orders.approve`,
  not `approve` — enforced at lint and boot like the name rule itself. Explicit, not
  auto-prefixed: silently rewriting codes would make the store show strings the author
  never wrote and break every grep. This is what keeps *applications* disjoint from each
  other (two apps both inventing `approve` would silently share one grant); disjointness
  from the framework is the mark's job. A concept two applications share (one approval
  authority across interlocking apps) is expressed where sharing lives — a *role* bundling
  `orders.approve` and `billing.approve` — which is Decision 26's rule (declarations never
  share) applied to authorization.
- The system applications are framework surfaces and speak framework atoms; the rule binds
  user applications' declared policies.

**Rejected: reserving the family words as application names** (this document's second
draft) — it inverts the fence's burden onto the user's namespace and grows by one reserved
word per future family; caught in review against the `/_tesseraql/` precedent.
**Rejected: `_tesseraql.` as the atom mark** — the right shape, the wrong length; `tql` is
already the framework's short mark (the CLI alias), and a grant reads
`tql.ops.view.orders`, not `_tesseraql.ops.view.orders`. (A leading underscore also sits
oddly in a permission list UI; the URL space keeps `_tesseraql` because it is already
shipped there and segment-visibility rules differ.)

**Rejected: the `.app`-marker draft** (`<family>.app.<name>` with dotted names kept legal) —
this document's own first answer, superseded in review by the simpler question "is the dot
freedom worth anything?": it is not, and withdrawing it deletes the marker segment, the
fence argument, and the name-versus-action ambiguity in one move. Kept here as the record
that the marker was load-bearing *only because* names could contain dots. **Rejected:
action suffixes on one family** (`ops.app.<name>.deploy`) — the shape that started the
grammar question; moot under the dot ban but rejected on its own terms too, since view and
act are different authorities to grant, not modifiers of one. **Rejected: structured
grants** (JSON claims) — a second permission model beside the flat codes every surface,
seeder and identity pack speaks. **Rejected: framework surfaces checking roles** — roles
are the deployment's words; a framework that assigns them meaning turns every deployment's
role list into an API. **Rejected: one super-grant** — seeing, acting, deploying and
editing collapse into "admin", and the on-call reader gets the deploy pen.

## Structural decision 2: the ops shell is the ops-console app, hosted by the surface runtime, delegating over loopback

**The mount moves; the app largely stays.** The `ops-console` app stops mounting into hosted
members and mounts into the stack surface runtime instead (the portal config's
`ops-console.enabled: false` line is deleted; hosted members skip the provider). It answers
at the origin scope — `/_tesseraql/ops/console`, the address Decision 17 reserved — behind
the atoms of structural decision 1. The skip is keyed on being a hosted member — a
topology rule like the derived address, not a preference — so a member declaring
`tesseraql.apps.ops-console.enabled: true` under a host still gets no local copy (open
question 4).

**A single application is a stack of one, and gets the shell — corrected in review.** An
earlier draft treated "standalone" as a topology that keeps its per-app console. Under
Decision 12 there is no such topology: `dev` and `host` are the only ways to run, both
stand the gateway and the surface runtime up for one member exactly as for five, and the
one-member stack signs in at the origin and opens the origin shell — whose switcher simply
lists one entry. The word "standalone" below means only the **unhosted boot**: a
`TesseraqlRuntime` started directly, with no `HostContext` — integration tests and library
embedding — where no origin exists to host a surface or bounce to. That boot keeps the SPI
mounts as a fallback; it is a test-and-embedding footnote, not a deployment shape. Its
addresses are the pre-stack ones, stated so nobody expects a prefix (asked in review): with
no host to assign one, the runtime is the root of its own port — application routes at
`/…`, framework surfaces at `/_tesseraql/…` on that port. `/<name>/` exists only where a
host assigned it, and there the surfaces have moved to the origin.

**The switcher is the grant, applied to the member list.** The shell lists the stack's
members (the surface runtime already holds `stackMembers`) filtered by the caller's
`tql.ops.view.<name>`/`tql.ops.view.*` atoms — the filter `OpsScope` runs today, applied to
membership instead of rows. During a canary, a staged member shows **two entries** —
`orders` and `orders (canary)` — because runtime-local data (traces, lanes, slow SQL) is
exactly what an operator watches a ramp for, and neither a weighted roll nor a stable pin
can show the canary's ring on purpose (open question 2).

**Delegation is server-side, over loopback, with the caller's own credentials.** The shell's
routes call providers registered only on the surface runtime (the `PortalProviders`
precedent), and those providers make real HTTP calls (Decision 15) to the selected member's
internal port at its prefixed address — `http://localhost:<port><basePath>/_tesseraql/ops/…`
— forwarding the caller's session cookie (and CSRF token on actions). Sessions live in the
shared framework store, so the member authenticates the same principal and **re-runs its own
grant checks**: authorization stays at the member, and the shell adds
reach, not authority. The member's ops JSON API and providers are untouched — a member
still knows how to answer everything about itself; what it no longer carries is the
console's chrome. `HostContext.forSurface` grows the member-origin lookup (name and slot →
live internal port, the `portOf` shape the relay already uses), which stays correct across
replaces because it reads the host's live slots.

**Fan-out pages degrade per member, never whole.** The overview lists one card per visible
member, each filled by a delegated call under a short per-member timeout; an unreachable
member's card says so (a replace in progress, a crashed runtime) and the page renders. A
shell that 500s because one member is mid-replace would contradict Decision 29's requirement
from the observing side. Streams (the console's live pages ride SSE) are delegated
frame-by-frame — the relay already proved the streaming discipline, and the delegation
client follows it.

**What is deliberately not aggregated:** merged cross-application trace streams, unified
job tables, stack-wide dashboards. The stack architecture's out-of-scope list already says
"cross-application aggregate views beyond the switcher — metrics already label job runs by
application". The shell is a switcher with an overview, not a new observability product.

**Rejected: a browser-side aggregator** (shell JS fetching `/<name>/_tesseraql/ops/…`
same-origin). It honours "real HTTP" and keeps authorization at the member for free, but it
moves the console from server-rendered declarative pages to a JS application — against the
framework's own grain, and unusable by anything that is not a browser. **Rejected: the
shell reads member state in-process** — Decision 15's explicit counterfactual; it would
also couple `tesseraql-ops-ui` to the runtime module the boundary check forbids.
**Rejected: shipping per-member consoles *and* the shell** — two doors to the same rooms,
diverging; the per-member console under a host is removed in the same slice the shell
arrives, which is also `runtime-footprint.md`'s direction for the deploy image.

## Structural decision 3: the identity remainder — one door to one store

**IAM Admin moves to the origin.** The identity store is stack-scoped; five mounted copies
of `iam-admin` were five doors to one store, each under a different member's prefix. The
surface runtime mounts it (the portal config's disable is deleted), it answers at the origin
`/_tesseraql/admin/…` — where `shell.html`'s system-nav already points, base-relative — and
hosted members stop mounting it. The pair moves under the mark — `tql.iam.admin.view`/`tql.iam.admin.write` — with its semantics unchanged.

**The per-member `auth-ui`/`account` copies retire, and the bounce goes to the origin.**
Decision 24 put sign-in and the account surface at the origin; the member copies stayed as
harmless duplicates because the login bounce is base-relative. The remainder is exactly that
bounce: a hosted member's 401 redirect becomes **origin-absolute** — `/_tesseraql/login`
with the `redirect` parameter carrying the original *prefixed* path, so the round trip
returns to the member page that bounced. The member knows it is hosted (its `HostContext`
carries the base path), so the renderer switches target by topology, not by configuration.
Hosted members then stop mounting `auth-ui` and `account` — a stack of one included, since
its origin serves sign-in like any other stack's; only the unhosted boot (tests, embedding —
the correction above) keeps the mounts, because it has no origin to bounce to. `dev` hosts
through the gateway, so development gets the origin bounce too — the shape development and
production share is the point of the whole campaign.

**The `tql.app.use` fence lands here too**, because it is the same member security layer: after
authentication, before any route, an authenticated principal without `tql.app.use.<member>` is
refused — the business-user half of structural decision 1, enforced where the application's
own authentication already runs. The portal's tile filter reads the same atom, so what a
user sees and what they can reach are one answer.

A side effect worth naming: `servedApps` — the set `OpsScope` ANDs against — shrinks to the
member itself once the system apps stop mounting, which is what that set always meant.

## The deploy surface: the grants work's first customer

runtime-replace.md closed its open question 5 as "deferred to the grants work" and left a
target-shape sketch; this design is that work, and the sketch lands as the third slice:

- **The atom is `tql.app.deploy.<name>`** (structural decision 1) in the shared store, seeded
  like every other permission code. (runtime-replace.md sketched it as "`ops.app.<name>`
  gaining a *deploy* action" — honoured in substance, renamed by the model: deploying is an
  authority over the application as a unit, not a row-scope modifier.)
- **The surface is the stack surface runtime**: an authenticated endpoint that receives a
  `.tqlapp` (upload rides the existing file-transfer machinery), checks the caller's
  `tql.app.deploy.<name>` against the **package's declared name**, runs `AppUpgrader.preflight`,
  and writes the same intent files the reconciler already consumes. The reconciler stays the
  one mechanism; the endpoint is a pen with authentication, exactly as the file protocol's
  design promised. Refusals surface as the endpoint's response *and* are not written — a
  refused deploy leaves no intent.
- **The CLI grows the remote mode**: `tesseraql deploy <package> --url <origin>` with a
  bearer from `tesseraql token` — the dual shape `token` itself has (`--app` xor `--url`),
  here `--stack` xor `--url`. A pipeline deploys with a scoped token instead of install-root
  access, which is the requirement's sentence ("a team deploys only the applications it
  manages") made real.
- **An ops-console deploy page** rides the shell (slice 1's chrome) and calls the same
  endpoint. It can land with the endpoint or after it; the endpoint is the contract.

The boundary runtime-replace.md stated carries over verbatim: this is an operational
guardrail, not isolation between distrusting teams — those get separate stacks (Decision
27). And install-root access on the host machine remains stack-root; the endpoint adds a
narrower door, it does not narrow the wide one.

## Slices

Three PRs, each independently green and observable:

| # | Slice | Contents | End state |
| --- | --- | --- | --- |
| 1 | The model and the ops shell | The atom grammar in `OpsScope` (families/verbs); TQL-YAML-1405 gains the interior-dot refusal and the reserved name `tql` (lint + boot + docs); application policy codes must carry the application's name as their first segment (lint + boot); `ops.batch.view`/`ops.batch.run`/`ops.app.<name>` retire into `tql.ops.view.<name>`/`tql.ops.run.<name>` (routes, seeds, packs); `ops-console` mounts at the surface and delegates over loopback (providers + member-origin lookup on `HostContext`); switcher by `tql.ops.view`, canary as a second entry; per-member consoles retired under a host; fan-out overview with per-member degradation | One console per stack, one atom grammar, disjoint vocabularies; `hosting.md`'s per-app-console paragraph replaced |
| 2 | The identity remainder and the `tql.app.use` fence | `iam-admin` at the origin; hosted members stop mounting `auth-ui`/`account`/`iam-admin`; the 401 bounce goes origin-absolute with the prefixed `redirect`; the member fence refuses authenticated principals without `tql.app.use.<member>`; the portal's tiles filter by the same atom beside tenant entitlement; seeds gain the baseline; the unhosted boot (tests, embedding) unchanged | One sign-in door, one admin door; who may use an application is a grant, not a URL |
| 3 | The deploy surface | `tql.app.deploy.<name>`; the surface runtime's authenticated deploy endpoint writing the intent files; `deploy --url` with a bearer; the ops deploy page | A pipeline deploys one application with a scoped token and no install-root access |

Slice 1 first (it lands the model and the delegation pattern). Slice 2 needs 1's grammar
only; slice 3 needs 1's grammar and the surface runtime, nothing from 2. **Slice 8 — the
Studio shell — gets its own design after slice 1 ships**, on the delegation pattern and the
`tql.studio.edit.<name>` atom this document fixes; `runtime-footprint.md`'s payoff (the test
framework, GreenMail and JUnit leaving every deployment) arrives with that extraction, and
is deliberately not promised here.

## Guards

- **A framework surface checks atoms, never roles.** Roles stay the deployment's
  vocabulary; the one violation (Studio's `editRoles`) retires with slice 8. Pinned by the
  slices' tests as each surface lands.
- **Authorization never moves to the shell.** Every delegated call carries the caller's own
  credentials and the member re-checks the caller's atoms; a shell bug can widen
  what is *listed*, never what is *answered*. Pinned by a test, not by review.
- **The switcher and the portal are deny-by-default**: no `tql.ops.view` atoms → an empty
  switcher; no `tql.app.use` atoms → no tiles and no fence crossing.
- **The deploy endpoint checks the atom against the package's declared name** — not a
  request parameter — so a token scoped to `orders` cannot deploy `billing` by renaming an
  upload field. A refused deploy writes no intent.
- **No new atoms without a surface.** The table in structural decision 1 is exhaustive;
  a verb arrives only with the surface that checks it.
- **TQL-YAML-1405 (widened twice)** — an application name containing `.` is refused, and
  `tql` joins `assets` as a reserved name (the mark, not the family words — the second
  draft's family-word reservation is rejected in structural decision 1); both at lint and
  at boot, with the atom grammar named as the reason.
- **An application's permission codes carry its own name as their first segment** — lint
  and boot, so the framework's atoms and every application's codes are disjoint by
  construction; cross-application sharing happens in roles.

## Test plan

**Slice 1**
- The headline IT, in the `MultiAppGatewayIntegrationTest` arrangement: two members, a
  principal granted `tql.ops.view.a` only — the switcher lists `a`, member `b`'s pages answer
  404-shaped refusals through the shell exactly as `OpsActions.notFound` answers today, and
  the same principal's delegated `a` pages show `a`'s jobs.
- Authorization stays at the member: a delegated call forged with a different application's
  name reaches the member and is refused by the member's own scope check (assert the
  member's refusal, not the shell's).
- The verbs are per application now: a principal with `tql.ops.view.a` + `tql.ops.run.b` sees `a`
  and cannot act on it, can act on `b` — the asymmetry the retired two-axis model could
  not express, pinned as its regression test.
- Canary entry: stage a canary (the #862 operations), the switcher gains the second entry,
  and its trace page answers from the canary runtime's ring (distinct marker, the
  `MultiAppReplaceIntegrationTest` fixture shape).
- Degradation: stop one member mid-test (`discardCanary`/`retire` machinery), the overview
  renders with that member's card marked unreachable, HTTP 200.
- A hosted member serves no `/_tesseraql/ops/console` of its own; a one-member stack opens
  the origin shell with one switcher entry; an unhosted boot still serves its own console
  (the existing per-app console tests keep passing unhosted).
- SSE through the delegation: the console's live page streams frame-by-frame through shell +
  relay (the `StackRelayTest` timing shape, one more hop).
- The name rule: `tesseraql.app.name: orders.eu` is refused at lint and at boot naming the
  atom grammar; `tesseraql.app.name: tql` is refused as the reserved mark, while `ops` and `studio` stay legal names; `受注管理`
  stays legal too.
- The code rule: an application policy referencing a permission code that does not start
  with the application's own name is refused at lint and boot; `orders.approve` passes,
  `approve` and `tql.ops.view.orders` are refused with the fence named.

**Slice 2**
- The bounce: an unauthenticated browser GET on `/<member>/page` 302s to
  `/_tesseraql/login?redirect=%2F<member>%2Fpage`; signing in at the origin returns to the
  member page; the member serves no `/_tesseraql/login` of its own anymore.
- The fence: an authenticated principal without `tql.app.use.<member>` is refused by the member
  on every route; with the grant, served; a route declaring `auth: none` answers either way;
  a JWT service caller meets the same fence.
- The portal: tiles = tenant entitlement ∧ `tql.app.use` atoms; a user with no `tql.app.use` grants
  sees an empty portal, not every entitled member.
- IAM Admin answers at the origin behind `tql.iam.admin.view`; no member serves
  `/_tesseraql/admin/…`.
- A one-member stack signs in at the origin like any other; the unhosted boot keeps all
  five mounts and the base-relative bounce (the existing direct-runtime tests pin it).
- `servedApps` shrinkage: `tql.ops.view.<member>` sees the member's rows and nothing about the
  system apps.

**Slice 3**
- The endpoint: a bearer with `tql.app.deploy.orders` deploys an `orders` package (intent files
  land exactly as `tesseraql deploy` writes them; the reconciler IT arrangement picks them
  up); the same bearer refused for a `billing` package; no atom → 403; a preflight
  refusal (not newer) surfaces and writes nothing.
- `deploy --url` end-to-end against a running stack: token → upload → intent → reconciler →
  new version serving (the #864 IT arrangement plus the endpoint).
- `--stack` xor `--url` refusals mirror `token`'s.

## What moves in the docs, and when

With the code PRs, not before: `hosting.md` (the per-app-console paragraph at
`hosting.md:287-297` is replaced by the shell + switcher + atom sentences; the deploy
section gains the endpoint and `--url`), `ops-console.md` (the shell, the switcher, the
canary entry, the permission table moving to `tql.ops.view`/`tql.ops.run`),
`authentication.md`/`account.md` where the login copies are described,
`deployment.md` (the `--admin-permissions` examples move to the atoms:
`tql.ops.view.*`, `tql.ops.run.*`, `tql.app.deploy.*`, `tql.app.use.*`;
`IdentitySchemaMojo`'s javadoc example is corrected in passing — it spells
`iam:admin:write` with colons while the iam-admin routes check `iam.admin.write`, so an
operator following it seeds a permission nothing matches),
`identity.md`/`iam-admin.md` wherever roles-versus-permissions is taught (the personas and
the canonical bundles belong there), `root-portal.md` (its deliberately-not list shrinks as
items land, and its tile-filter sentence gains the `tql.app.use` axis),
`app-isolation-model.md` (Decision 4's reversal note points at the shipped shell),
TQL-YAML-1405's reference row (the widened rule), `reference-cli.md` regeneration
(`deploy --url`), the error reference for any new codes the slices mint, `CHANGELOG.md` per
slice, and the shipped-status notes: stack-architecture Decision 14 + open question 4, and
runtime-replace.md's authorization section (its "until then" interim paragraph gets the
arrival note, and its `ops.app.<name>`-gains-a-deploy-action sketch the rename note).

## Deliberately not in this design

- **The Studio shell (slice 8).** Its own design, after slice 1: the delegation pattern and
  `tql.studio.edit.<name>` are fixed here; everything else — preview, edit, apply, the test
  runner, the extraction that fixes `runtime-footprint.md`'s dependency complaint — is that
  document's scope.
- **A permission registry.** Atoms stay strings in the store; a catalogue of valid codes
  with UI affordances is a different feature. The grammar makes them *predictable*, which
  is the registry's cheapest half.
- **Shipped role bundles.** The canonical bundles (operator, deployer, developer, per-app
  user roles) are documented, not seeded — a deployment's roles are its own words, and a
  framework that seeds them turns examples into API (open question 7).
- **Per-tenant atoms.** Tenant entitlement stays the catalogue's axis; the store's atoms are
  per-principal. Two questions, two mechanisms, deliberately.
- **Cross-application aggregate views beyond the switcher and the overview cards** — the
  stack architecture's standing exclusion.
- **Multi-stack ops.** Nothing above a stack (Decision 27).

## Open questions

Each gated on the slice it blocks, with a recommendation:

1. **The atom grammar and the name rules** — *gates slice 1.* Marked atoms
   (`tql.<family>.<verb>.<name|*>`) over dot-free names with `tql` as the one reserved
   name, versus the two earlier drafts: the `.app`-marker grammar that kept dotted names
   legal, and the family-word reservation the `tql.` mark replaced. Recommended: the
   marked grammar — the dot freedom buys nothing measured, the mark follows the
   `/_tesseraql/` philosophy (the framework marks its own space), and future families
   never touch the name rules again. (Direction set in review twice; recorded as a
   question so the reversal of the shipped 1405 javadoc sentence is an explicit decision.)
2. **What a canary shows in the switcher** — *gates slice 1.* Recommended: a second entry
   for the staged slot (`orders (canary)`), because runtime-local data is what an operator
   watches a ramp for; the alternative (delegate through the weighted roll the front uses)
   makes the trace page a coin flip.
3. **The unhosted boot keeps the per-app surfaces** — *gates slice 2.* Reframed in review:
   there is no "standalone" deployment topology under Decision 12 — a single application is
   a stack of one and uses the origin's surfaces like any other stack. What this question
   covers is only the boot with no host at all (`TesseraqlRuntime` started directly:
   integration tests, library embedding), which has no origin to host a surface or bounce
   to. Recommended: that boot keeps the SPI mounts, as a fallback rather than a shape.
4. **May a hosted member keep its own console by config?** — *gates slice 1.* Recommended:
   no. One console per stack is a topology rule, like the derived address — a per-member
   opt-out would be the two-doors shape this design exists to remove.
5. **Does the deploy endpoint land with the ops page or before it?** — *gates slice 3's
   internal ordering only.* Recommended: endpoint first, page with or after it; the
   endpoint is the contract, the page is chrome.
6. **`tql.app.use` enforcement: the full member fence, or the portal's tiles only?** — *gates
   slice 2.* Recommended: the full fence, deny-by-default for every authenticated
   principal (browser and service alike; `auth: none` routes untouched) — reach as a
   grant is the business-user half of the model, and a tiles-only filter would be
   decoration over an open door. The cost is stated in structural decision 1: adopting
   stacks seed `tql.app.use` grants or a `tql.app.use.*` baseline role before users sign in;
   `dev`'s bootstrap admin carries the wildcard.
7. **Are the canonical role bundles shipped as seeds, or documented only?** — *gates
   nothing; shapes the identity pack.* Recommended: documented only (the deliberately-not
   entry above); the identity pack keeps seeding one admin with the wildcards it already
   teaches.
