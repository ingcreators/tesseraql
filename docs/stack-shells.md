# Framework surfaces at stack scope

Implementation design for what remains of [stack-architecture.md](stack-architecture.md)
Decision 14: the ops console becomes a stack-level shell with an application switcher
(slice 7), the permission vocabulary that shell forces is decided (open question 4), and the
identity surfaces finish their move to the origin scope (Decision 24's slice-4 remainder:
IAM Admin at the origin, the per-member `auth-ui`/`account` copies retired). It also designs
the authenticated deploy surface that [runtime-replace.md](runtime-replace.md) deferred here —
its open question 5 closed as "deferred to the grants work", and this is the grants work.

**Studio (slice 8) is deliberately not designed here.** The stack architecture's own slices
section says why: "slice 8 is a campaign, not a slice" — `StudioService` couples preview,
editing, apply/reload, the scaffolder, the migration author and the test runner to a runtime,
and it is designed separately **on the delegation pattern this document establishes**. What
this document does decide for Studio is its grant family (structural decision 1), so the
vocabulary is settled once.

Written 2026-08-18, before implementation. Everything below was measured against main at #865
unless marked otherwise.

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

**`ops.app.<name>` has exactly one parser, and its semantics are two ANDed filters.**
`OpsScope.allowedApps(permissions, servedApps)` (`tesseraql-ops-ui`, `OpsScope.java:44-48`)
returns `served.contains(app) && granted.test(app)`: what this runtime serves, and what the
caller was granted — `ops.app.*` for everything, else exact string membership. Entry
permissions are separate: every console route carries `ops.batch.view` (reads) or
`ops.batch.run` (actions), and the scoped grants narrow rows. Out-of-scope and unknown are
indistinguishable by design (TQL-BATCH-4040, `OpsActions.java:33-42`). Trace visibility keys
on the root span's `app` attribute (`OpsDashboard.java:387-397`); lanes, slow SQL, pinning
and aggregate metrics are deliberately runtime-wide behind the entry permission only.
`servedApps` today includes the mounted system apps (`TesseraqlRuntime.java:1370-1378`), not
just the member.

**The permission is a free string; no registry exists.** `ops.app.<name>` is compared by
prefix in `OpsScope` and nowhere else; the identity pack's sample rows literally grant
`ops.app.*`; IAM Admin uses a flat `iam.admin.view`/`iam.admin.write` pair. There is no
action sub-grammar anywhere — relevant because runtime-replace.md's deploy authorization
sketch wants `ops.app.<name>` "gaining a *deploy* action".

**And an application's name can contain dots.** `ApplicationName.segmentViolation` fences
`/`, a leading `_` or `.`, and the reserved word `assets` — nothing else, and non-ASCII is
deliberately legal (TQL-YAML-1405's own javadoc). So `orders.eu` is a valid name, and any
grammar that appends an action after the name — `ops.app.orders.eu` versus
`ops.app.orders.deploy` — cannot tell a name from a name-plus-action. This measurement
decides structural decision 1.

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

**The portal's filter widens in one place.** `PortalProviders` filters tiles by tenant
entitlement only, with the comment: "Per-principal application grants are deliberately not
invented here; when that model lands (stack-architecture.md open question 4), this filter
widens in one place." This design decides what that model is — and recommends *not* widening
the portal yet (open question 6).

**What `hosting.md` currently promises moves with this design.** "The operations console is
per application … `ops.app.<name>` is the permission to open an application's console. An
operator running a stack therefore has one console per application rather than one screen
listing every application's jobs" (`hosting.md:287-297`). That paragraph is the shape this
design replaces, and `app-isolation-model.md` Decision 4 ("the ops console is per-app") is
formally reversed by stack-architecture Decision 14 — the reversal is recorded there already;
the prose moves with the code.

## Structural decision 1: the grant vocabulary is fixed-prefix families

The vocabulary is **`<family>.app.<name>`**, one family per surface capability:

- **`ops.app.<name>`** — unchanged string, unchanged meaning, wider window: *may see this
  application's operational data*. Today that narrows rows inside one runtime; under the
  shell it also decides which applications appear in the switcher. Same grant, same
  sentence, read in one more place.
- **`studio.app.<name>`** — *may edit this application in Studio*. Reserved here, consumed
  by slice 8's own design; Decision 14 already states Studio "needs per-application edit
  authorisation on the switch rather than a single may-open-Studio role".
- **`deploy.app.<name>`** — *may deploy this application*: the action
  runtime-replace.md's authorization section sketched, carried by the deploy surface below.

`<name>` is the **verbatim remainder after the constant prefix**, and the wildcard is
`<family>.app.*`. That is the whole grammar, and it is dictated by the measurement above: a
name can contain dots, so an action *suffix* (`ops.app.<name>.deploy`) is ambiguous — the
parser cannot tell `orders.deploy` the application from `orders` plus an action. A fixed
prefix keeps the name whole, keeps `OpsScope`'s exact-string membership semantics, and adds
no parsing beyond the prefix compare that already exists. `OpsScope` stays the one parser,
parameterized by family; entry permissions (`ops.batch.view`/`ops.batch.run`,
`iam.admin.view`/`iam.admin.write`) are untouched — a family grant narrows *which
applications*, an entry permission opens *the surface*.

**The `.app` segment is load-bearing, not decoration** (asked in review: is it always
needed?). Per-application grants share their namespace with the same family's
surface-wide permissions — `ops.batch.view` and `ops.batch.run` live beside
`ops.app.<name>` today — and names can contain dots, so the parser has only constant-prefix
matching to work with. A `ops.<name>` grammar would read the entry permission
`ops.batch.view` as a grant for an application named `batch.view`: entry permissions and
application grants would be parseable as each other. `<family>.app.` reserves a subtree in
which only names live, for every family alike — which is also why `deploy` and `studio`
adopt it even though neither has a surface-wide permission yet: the fence must exist before
the first non-app permission in the family, not be retrofitted after a collision.

`iam.admin.*` deliberately gains no `app` family: the identity store is the stack's
(Decision 22/24), there is no per-application axis to scope, and inventing one would grant
words with nothing behind them.

**Rejected: action suffixes on `ops.app.<name>`** — ambiguous, measured above; this is also
a small correction to runtime-replace.md's sketch ("`ops.app.<name>` gaining a deploy
action"), which is honoured in substance (a per-application deploy grant in the shared
store) with a grammar that survives dotted names. **Rejected: structured grants** (JSON
claims with fields) — a second permission model beside the flat codes every surface,
seeder and identity pack already speaks. **Rejected: one super-grant** ("`ops.app.<name>`
implies deploy") — seeing operational data and moving versions are different authorities;
collapsing them would hand every on-call reader the deploy pen.

## Structural decision 2: the ops shell is the ops-console app, hosted by the surface runtime, delegating over loopback

**The mount moves; the app largely stays.** The `ops-console` app stops mounting into hosted
members and mounts into the stack surface runtime instead (the portal config's
`ops-console.enabled: false` line is deleted; hosted members skip the provider). It answers
at the origin scope — `/_tesseraql/ops/console`, the address Decision 17 reserved — behind
the same entry permissions as today. The skip is keyed on being a hosted member — a
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
mounts as a fallback; it is a test-and-embedding footnote, not a deployment shape.

**The switcher is the grant, applied to the member list.** The shell lists the stack's
members (the surface runtime already holds `stackMembers`) filtered by the caller's
`ops.app.<name>`/`ops.app.*` grants — the same filter `OpsScope` runs today, applied to
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
entry-permission and scope checks**: authorization stays at the member, and the shell adds
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
hosted members stop mounting it. `iam.admin.view`/`iam.admin.write` are unchanged.

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

A side effect worth naming: `servedApps` — the set `OpsScope` ANDs against — shrinks to the
member itself once the system apps stop mounting, which is what that set always meant.

## The deploy surface: the grants work's first customer

runtime-replace.md closed its open question 5 as "deferred to the grants work" and left a
target-shape sketch; this design is that work, and the sketch lands as the third slice:

- **The grant is `deploy.app.<name>`** (family, structural decision 1) in the shared store,
  seeded like every other permission code.
- **The surface is the stack surface runtime**: an authenticated endpoint that receives a
  `.tqlapp` (upload rides the existing file-transfer machinery), checks the caller's
  `deploy.app.<name>` against the **package's declared name**, runs `AppUpgrader.preflight`,
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
| 1 | The vocabulary and the ops shell | `OpsScope` families; `ops-console` mounts at the surface and delegates over loopback (providers + member-origin lookup on `HostContext`); switcher filtered by `ops.app.<name>`, canary as a second entry; per-member consoles retired under a host; fan-out overview with per-member degradation | One console per stack; `hosting.md`'s per-app-console paragraph replaced |
| 2 | The identity remainder | `iam-admin` at the origin; hosted members stop mounting `auth-ui`/`account`/`iam-admin`; the 401 bounce goes origin-absolute with the prefixed `redirect`; the unhosted boot (tests, embedding) unchanged | One sign-in door and one admin door; `servedApps` = the member |
| 3 | The deploy surface | `deploy.app.<name>`; the surface runtime's authenticated deploy endpoint writing the intent files; `deploy --url` with a bearer; the ops deploy page | A pipeline deploys one application with a scoped token and no install-root access |

Slice 1 first (it lands the vocabulary and the delegation pattern). Slice 2 is independent
of 1 in code but reads better after it (the origin's surfaces arrive together). Slice 3
needs 1's vocabulary and the surface runtime, nothing from 2. **Slice 8 — the Studio
shell — gets its own design after slice 1 ships**, on the delegation pattern and the
`studio.app.<name>` family this document fixes; `runtime-footprint.md`'s payoff (the test
framework, GreenMail and JUnit leaving every deployment) arrives with that extraction, and
is deliberately not promised here.

## Guards

- **Authorization never moves to the shell.** Every delegated call carries the caller's own
  credentials and the member re-checks entry permission and scope; a shell bug can widen
  what is *listed*, never what is *answered*. Pinned by a test, not by review.
- **The switcher is deny-by-default**, like the rows today: no `ops.app.*` grants → an empty
  switcher, not every member.
- **The deploy endpoint checks the grant against the package's declared name** — not a
  request parameter — so a token scoped to `orders` cannot deploy `billing` by renaming an
  upload field. A refused deploy writes no intent.
- **No new grant families without a surface.** The vocabulary admits exactly the three
  families with a consumer (`ops`, `studio` reserved for slice 8, `deploy`); the portal's
  tile filter stays tenant-entitlement (open question 6) rather than growing a fourth family
  speculatively.

## Test plan

**Slice 1**
- The headline IT, in the `MultiAppGatewayIntegrationTest` arrangement: two members, a
  principal granted `ops.app.a` only — the switcher lists `a`, member `b`'s pages answer
  404-shaped refusals through the shell exactly as `OpsActions.notFound` answers today, and
  the same principal's delegated `a` pages show `a`'s jobs.
- Authorization stays at the member: a delegated call forged with a different application's
  name reaches the member and is refused by the member's own scope check (assert the
  member's refusal, not the shell's).
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

**Slice 2**
- The bounce: an unauthenticated browser GET on `/<member>/page` 302s to
  `/_tesseraql/login?redirect=%2F<member>%2Fpage`; signing in at the origin returns to the
  member page; the member serves no `/_tesseraql/login` of its own anymore.
- IAM Admin answers at the origin behind `iam.admin.view`; no member serves
  `/_tesseraql/admin/…`.
- A one-member stack signs in at the origin like any other; the unhosted boot keeps all
  five mounts and the base-relative bounce (the existing direct-runtime tests pin it).
- `servedApps` shrinkage: `ops.app.<member>` sees the member's rows and nothing about the
  system apps.

**Slice 3**
- The endpoint: a bearer with `deploy.app.orders` deploys an `orders` package (intent files
  land exactly as `tesseraql deploy` writes them; the reconciler IT arrangement picks them
  up); the same bearer refused for a `billing` package; no grant → 403; a preflight
  refusal (not newer) surfaces and writes nothing.
- `deploy --url` end-to-end against a running stack: token → upload → intent → reconciler →
  new version serving (the #864 IT arrangement plus the endpoint).
- `--stack` xor `--url` refusals mirror `token`'s.

## What moves in the docs, and when

With the code PRs, not before: `hosting.md` (the per-app-console paragraph at
`hosting.md:287-297` is replaced by the shell + switcher + grant sentences; the deploy
section gains the endpoint and `--url`), `ops-console.md` (the shell, the switcher, the
canary entry), `authentication.md`/`account.md` where the login copies are described,
`deployment.md` (the `--admin-permissions` examples gain `deploy.app.*`),
`root-portal.md` (its deliberately-not list shrinks as items land),
`app-isolation-model.md` (Decision 4's reversal note points at the shipped shell),
`reference-cli.md` regeneration (`deploy --url`), the error reference for any new codes the
slices mint, `CHANGELOG.md` per slice, and the shipped-status notes: stack-architecture
Decision 14 + open question 4, and runtime-replace.md's authorization section (its "until
then" interim paragraph gets the arrival note).

## Deliberately not in this design

- **The Studio shell (slice 8).** Its own design, after slice 1: the delegation pattern and
  `studio.app.<name>` are fixed here; everything else — preview, edit, apply, the test
  runner, the extraction that fixes `runtime-footprint.md`'s dependency complaint — is that
  document's scope.
- **Portal tile narrowing by grants.** The tiles are reach for end users; `ops`/`studio`/
  `deploy` are operator authorities. Conflating them would hide a business application from
  its users because they cannot administer it. If per-principal reach is ever wanted, it is
  its own family and its own yes/no (open question 6 records the recommendation: not now).
- **A permission registry.** Grants stay free strings compared by prefix; a catalogue of
  valid codes is a different feature with its own UI implications.
- **Cross-application aggregate views beyond the switcher and the overview cards** — the
  stack architecture's standing exclusion.
- **Multi-stack ops.** Nothing above a stack (Decision 27).

## Open questions

Each gated on the slice it blocks, with a recommendation:

1. **The grammar** — *gates slice 1.* Fixed-prefix families (`<family>.app.<name>`) versus
   action suffixes on `ops.app.<name>`. Recommended: families — the dotted-name measurement
   makes suffixes ambiguous, and the sketch in runtime-replace.md is honoured in substance.
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
6. **Portal tiles narrowed by a grant family** — *gates nothing; recorded so the portal's
   one-place comment has an answer.* Recommended: not now, for the reach-versus-authority
   reason above; tenant entitlement stays the tile filter.
