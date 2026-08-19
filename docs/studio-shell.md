# The Studio shell and the workshop extraction

Implementation design for [stack-architecture.md](stack-architecture.md) Decision 14's slice 8,
the one [stack-shells.md](stack-shells.md) deliberately left out: Studio becomes a stack-level
shell on the delegation pattern the ops shell established, checked by the
`tql.studio.edit.<name>` atom that document reserved, and the one standing violation of
"a framework surface checks atoms, never roles" — Studio's `editRoles` — retires. The same
work delivers [runtime-footprint.md](runtime-footprint.md) Decision 3, its second, independent
justification: the runtime module stops depending on a test framework, which is what removes
GreenMail and JUnit 4 from the path to every deployment's classpath.

Written 2026-08-19, before implementation. Everything below was measured against main at #892
unless marked otherwise.

**Status 2026-08-19: design approved in review.** All five open questions closed on their
recommendations — `readOnly` retires outright, the loss of production members' read-only
Studio is accepted and recorded as the named cost, the workshop keys on DevMode *and* the
stack being source trees, Copilot is proxied from the member, and the module is
`tesseraql-studio-runtime`. One clarification from review is recorded in structural
decision 1's rejection list: a future "start editing an installed package" need is an
explicit take-it-out-of-the-package operation (the eject lineage), never a loosening of the
workshop condition. Implementation proceeds slice by slice, slice 1 first.

**Slice 1 is shipped** (the extraction): `tesseraql-studio-runtime` exists as designed —
`StudioProviders`, `DocsProviders`, the JSON API, `StudioTestService`, the scaffold and data
services, `StudioAccess`, the Copilot transports and the doc cache moved wholesale;
`RouteReloader` stays with `--watch`, publicized, its Studio fields traded for `onReload`
listeners; the boot facts the inlined wiring reached as locals ride one `RuntimeSeams` bean
bound before extensions install; and the runtime pom's Studio and test-core dependencies are
gone, guarded by an enforcer rule (compile/runtime scope banned, test scope free). Three
implementation decisions the design left open, recorded here: the studio-only helpers in the
runtime boot's tail (the try-it console, the policy-engine rebind, the render-PDF paths,
~20 statics) moved into the module as `StudioSupport`; the Studio-shaped tests moved with the
machinery (Maven's cycle rule forbids the runtime module test-depending on the extension), the
member-mounted-Studio-under-a-prefix property re-pinned from the workshop module's own
`StackStudioIntegrationTest`; and the framework-surface guard moved too, because the workshop
module's test classpath is the one place every surface family can mount at once.

**Slice 2 is shipped** (the atom): `StudioEdit` checks `tql.studio.edit.<name>` — shell-side
reach comes with slice 3; here it is the workshop's own gate — and `StudioAccess`,
`editRoles`, `readOnly` and `TQL-STUDIO-4031` are gone (the refusal is the standard 403,
naming the atom). `StudioService` keeps its writability parameter as library API (the MCP
dev tools construct read-only instances deliberately); the extension constructs it writable,
per structural decision 4's invariant. The studio app's route bindings moved from
`principal.roles` to `principal.permissions`, the capability switches lost their
`!readOnly` conjunct, and the bootstrap baseline gained `tql.studio.edit.*`.

**Slice 3 is shipped** (the shell): `studio` joined the topology skips; the host computes the
workshop verdict (`dev` ∧ no `catalog.json`) and hands it down through `HostContext`; the
surface mounts the studio app through a topology graft over the portal's static disable; the
studio app tree went member-shaped everywhere — the ops-console `{member}` precedent, the
unhosted boot serving the same tree over an in-process target as a switcher of one; the
member's workshop API (`/_tesseraql/studio/data/{op}`) answers the export table on enumerated
verbs, stamps `permissions` and `actor` from its own authenticated principal, and refuses
without the atom 404-shaped (`TQL-STUDIO-4043`; unreachable is `TQL-STUDIO-5030`); and
Copilot's send is the one proxied hop, its stream reached at the member's own prefixed
address through the gateway. Four implementation decisions the design left open, recorded
here: the member segment rides the same one-place emission channel as the base path — the
Thymeleaf link builder and the redirect renderer rewrite studio-addressed targets when a
member page renders, the `/_as/<role>` precedent, so the app tree's two-hundred-odd link
expressions stay member-agnostic; scalar provider results (the CSV export, generated files)
ride the delegation in a value envelope with byte arrays base64-marked, and the file-response
ops take no shell chrome; the token-authorized docs-share pages bypass the atom on both sides
(the signed link is the authorization, verified by the provider itself, exactly as they
bypass the browser session); and the shell's transport keys are its own
(`shellCookie`/`shellCsrf`), so a page whose own feature params are named `cookie`/`csrf` —
the try-it console — forwards them as data.

## What exists today, measured

**Half of the extraction is already done, and it is the half everyone assumes is missing.**
`StudioService` (tesseraql-studio, 1,890 lines) holds no runtime: its constructor takes an
`AppManifest`, a read-only flag and an `ExpressionFunctions` registry and nothing else
(`StudioService.java:76-120`), and database, field-masking and PDF access are inverted through
four SAM interfaces the runtime implements (`FieldMask` `:304-309`, `PdfRender` `:317-322`,
`RowSource` `:330-340`, `DdlDryRun` `:986-990`). The studio module's pom does not depend on
`tesseraql-camel-runtime` — the Decision 15 boundary holds.

**The coupling is all in the runtime module, and it is large.** `tesseraql-camel-runtime`
declares compile dependencies on `tesseraql-studio` and `tesseraql-test-core`
(`tesseraql-camel-runtime/pom.xml:117-127`) and carries: `StudioProviders` (2,067 lines,
83 `studio.*` providers, whose `Deps` record captures twenty boot-time objects,
`StudioProviders.java:55-66`), `DocsProviders` (18 `docs.*` providers), `StudioRouteBuilder`
(the bearer-gated JSON API), `StudioTestService` (393 lines — the **only** consumer of
`tesseraql-test-core`, whose transitive chain is GreenMail and then JUnit 4,
[runtime-footprint.md](runtime-footprint.md) problem 2), `StudioAccess` (the role allow-list),
the Copilot wiring, and `StudioDocCache`. `RouteReloader` — which `--watch` also drives, so it
is not Studio-only — holds a `StudioService` and the doc cache and calls both at the end of
every reload (`RouteReloader.java:257-260`).

**Studio still mounts on every hosted member.** The topology skip set is
`{ops-console, auth-ui, account, iam-admin}` (`TesseraqlRuntime.java:1300-1303`); `studio` is
absent from it, #870 having explicitly deferred it here. The portal's config disables Studio at
the surface with a comment naming this design as the reason
(`tesseraql/apps/portal/config/tesseraql.yml:10-16`), and `_system`'s `studioHref` is the one
link with no origin-absolute branch (`TesseraqlRuntime.java:1335-1337`).

**Under a host, a writable Studio would edit an installed package, and only a config default
prevents it.** A hosted member's app home is the extracted package tree under the install root
(`MultiAppHost.java:240`); `applyDraft`, `scaffoldApply` and `appendRecordedTest` write into
that tree, the reconciler's replace destroys the writes (`AppInstaller` deletes and re-moves
the directory), and `studio.migration.migrate` runs Flyway against the live datasource — an
unversioned schema change against a package the deployment believes immutable. What stands
between a production member and all of that is `tesseraql.studio.readOnly` defaulting to
`true` (`TesseraqlRuntime.java:2161`) — a configuration default, not a topology rule.
`DraftStore`'s only guard is path traversal (`DraftStore.java:56-65`), which is a different
question.

**The role allow-list is the model's one standing violation.** `StudioAccess.canEdit` matches
`principal.roles` against `tesseraql.studio.editRoles` (`StudioAccess.java:59-70`,
`TQL-STUDIO-4031`) — a framework surface assigning meaning to the deployment's role names,
exactly what [stack-shells.md](stack-shells.md) structural decision 1 forbids and recorded as
retiring here. `Atoms.STUDIO_EDIT_PREFIX` is already reserved with no caller
(`Atoms.java:29-30`), and the bootstrap `--admin-permissions` default
(`IdentitySchemaMojo.java:72-75`) does not yet include the studio wildcard.

**The delegation pattern is shipped and proven, with the details this design inherits.** The
ops shell (#869): an `Op` table mapping provider names to member-side paths and verbs;
`Targets.of` making real HTTP over loopback with the port resolved per call through
`HostContext.MemberOrigins` (live across replaces), forwarding the caller's `Cookie` header and
`X-CSRF-Token` on actions; a member 404 read as out-of-scope-or-unknown, any other failure as
unreachable (`TQL-BATCH-5030`, 503-shaped); `Targets.self` as the in-process face for the
unhosted boot; member-side JSON routes under `/_tesseraql/ops/data/*` that authenticate the
browser session and re-run the member's own scope check (`requireMemberView`, 404-shaped
`TQL-BATCH-4040`) before invoking the same view-model providers the old console rendered; a
`{member}` path segment threading through the app tree; per-member degradation on fan-out
pages; and a proxy route for downloads. The shell adds reach, never authority.

**Two Studio surfaces do not fit the view-model-over-JSON shape.** The test runner is a
synchronous call (`StudioTestService.runForPath`, one blocking evaluation on the request
thread), so it delegates like any other op but needs its own time bound rather than the page
default. Copilot is a genuine SSE stream (`CopilotRouteBuilder` plus the runtime's SSE
endpoint) — the one Studio page that streams — and the export/PDF/docs-share responses are
downloads. Streams and downloads take the proxy-route shape, not the view-model shape.

**The development-tool MCP is a different process and is untouched.** `McpDevTools` (CLI)
imports `StudioService` directly over a map of app homes; it never touches the runtime's
Studio instance. The CLI keeps its `tesseraql-studio` dependency.

**The unhosted boot keeps its Studio today through the SPI**, like every other surface:
`StudioAppProvider` contributes the app when `tesseraql.studio.enabled` allows it, and the
runtime wires the services. Stack-shells open question 3 already settled the stance this
design inherits: the unhosted boot (integration tests, library embedding) keeps its fallback
mounts.

## Structural decision 1: the workshop exists only where the source is

Studio is the workshop: it edits a source tree and reloads the runtime that serves it. Under
Decision 12 there is one deployment shape, so the question is not "which topology" but "which
stack": `dev` runs source trees being edited; `host` runs what `deploy` delivered. The
workshop follows the source:

- **In a `dev` stack, the shell mounts on the surface runtime and the workshop API mounts on
  every member.** The developer signs in at the origin and opens `/_tesseraql/studio` — the
  address Decision 17 reserved — exactly as the operator opens the ops console.
- **In a `host` stack, nothing Studio mounts anywhere, and no configuration turns it on.**
  This is a topology rule like the derived address and the no-console-opt-out answer (stack-
  shells open question 4): a member declaring `tesseraql.apps.studio.enabled: true` under a
  host still gets no Studio. The hazard is measured above — edits to an installed package are
  destroyed by the next replace, and the migration author writes unversioned schema changes —
  and a rule that a config key can waive is the `readOnly`-default posture this design
  retires. Changes reach a host through `deploy`; that is what [runtime-replace.md](runtime-replace.md) built.
- **A `dev` stack pointed at an install root gets no workshop either.** `dev --stack` accepts
  a catalogue; the members are then installed packages, and the same hazard applies. The
  workshop keys on both facts the host already knows: DevMode is present *and* the stack is
  source trees, not a catalogue. Only the host can know either — Decision 16's rule — so the
  host computes the flag and hands it down through `HostContext`.
- **The unhosted boot keeps the full local Studio**, fallback mounts unchanged, because it has
  no origin to host a shell — the stack-shells correction applies verbatim. Its Studio is
  writable to holders of the atom (structural decision 4), which tests and embedders grant
  through their own fixtures.

**The named cost: the read-only Studio that production members carry today disappears.** A
deployed member currently serves `/_tesseraql/studio/ui` read-only by default — in practice a
documentation surface (routes, schema, coverage, docs share links). Under this design the docs
surface exists where the workshop exists: in `dev`. A documentation surface *for production*
is a real feature with a different audience and no write machinery, and it is deliberately not
smuggled in here as a read-only mode of the workshop (open question 2).

**Rejected: a config-gated Studio under `host`.** Every argument for it is an argument for a
production docs surface, and it keeps the write machinery one key away from an installed
package. **Rejected: keying the workshop on classpath presence** (the extension jar being
there) — the dev CLI and a hand-run host share a classpath today; presence says what *could*
run, topology says what *should*. **Rejected: loosening the workshop condition for a future
"start editing an installed package" need** (raised in review) — a `dev` over an install root
is a run-and-observe shape (package verification, production repro, pipeline smoke, demos),
and every one of those uses is betrayed by edits landing in the extracted tree; if editing
from a package is ever wanted, it is an explicit take-it-out-of-the-package operation — the
eject lineage — into a workspace, never a workshop over the package.

## Structural decision 2: the shell at the origin, the workshop API at the member

The ops shell's shape, applied with Studio's own exceptions named.

**The mount moves; the app largely stays.** The `studio` app (tesseraql-studio's classpath
tree) stops mounting into hosted members (`studio` joins the topology skip set) and mounts
into the surface runtime when the workshop flag says so. Its home becomes the switcher —
members filtered by the caller's `tql.studio.edit.<name>`/`.*` atoms, deny-by-default — and
its pages move under a `{member}` segment: `/_tesseraql/studio` lists the members,
`/_tesseraql/studio/{member}/ui/...` is the workshop for one member. The route files gain the
segment and the templates' absolute hrefs become `shell.base`-relative — the `studio-nav`
fragment alone carries ~32 of them. Large, mechanical, and one-shape-throughout; the ops
console's `{member}` tree is the template.

**Delegation is server-side, over loopback, with the caller's own credentials.** The surface's
`studio.shell.*` providers resolve the member's live internal port per call
(`HostContext.MemberOrigins`), forward the session cookie (and `X-CSRF-Token` on actions), and
call the member's workshop API at `http://localhost:<port><basePath>/_tesseraql/studio/data/…`.
The member authenticates the same principal against the shared session store and **re-runs its
own atom check** before touching anything — authorization stays at the member; the shell adds
reach, not authority. An unreachable member degrades that member's card or page
(the `TQL-BATCH-5030` discipline, with a Studio-domain code), never the shell.

**The member-side workshop API is enumerated, in one table, at registration.** The member
exposes `/_tesseraql/studio/data/*`: browser-authenticated, every route refusing a principal
without `tql.studio.edit.<member>` with a 404-shaped error (out-of-scope and unknown
indistinguishable, the `requireMemberView` discipline), actions additionally CSRF-checked. The
routes are generated from one explicit export table — provider name, verb, path, timeout
class — owned by the same registration that binds the providers. Not a hundred hand-written
route declarations, and **not** a generic invoke-any-provider endpoint: an implicit dispatch
would expose every future provider by default, and fail-open is the property the
silent-tolerance campaign spent thirty PRs deleting.

**Three surfaces take the proxy shape instead of the view-model shape:**

- **Copilot** is real SSE: the shell proxies the stream frame-by-frame with the caller's
  cookie — the relay proved the streaming discipline, and the delegation client follows it.
  The conversation state and the drafts it writes live at the member, where the app home is.
- **Downloads** (data export, docs export, OpenAPI, route PDFs, docs share) proxy bytes with
  the download timeout, the transfer-file precedent.
- **The test runner** delegates as an ordinary op but under its own bound derived from the
  runner's limits (`queryTimeoutSeconds` × a case budget, settled at slice time), not the
  15-second page default — a suite is allowed to be slower than a page.

**Apply and reload stay at the member.** `RouteReloader` holds the member's `CamelContext`;
the shell's apply POST delegates to the member, the member writes the draft over the source
and reloads itself, and the answer rides back. `--watch` keeps driving the same reloader
per runtime, untouched. The audit trail records the forwarded principal — the same actor
discipline the ops shell pinned.

**No canary entries in the switcher, deliberately — the one departure from the ops shell.**
The reconciler exists only for the install-root shape, so a `dev` stack — the only place the
workshop mounts — has no canary slots. And the workshop's subject is the member's *source
tree*, which slots share by construction. The switcher lists one entry per member, and the
`slot` machinery is not threaded through. If a canary ever appears where a workshop runs, the
delegation resolves the stable slot.

**`_system` gains the missing branch.** A hosted member's `studioHref` becomes origin-absolute
(`/_tesseraql/studio`) when the workshop is on, exactly as `consoleHref` and `iamHref` did in
#869/#870; under a non-dev host it renders nothing, so the shell never links a 404. The
`vscode://` editor links keep working: `dev` is one machine, and the member app homes the
shell's pages name are local paths there.

**Rejected: a browser-side aggregator** — the same grounds as the ops shell (a JS application
against the framework's grain). **Rejected: members keep the Studio UI and the shell frames
or links to it** — the two-doors shape, plus it would keep the UI answering under member
prefixes the fence must then special-case. **Rejected: the shell renders while calling
member *services* in-process through the host** — Decision 15's explicit counterfactual.

## Structural decision 3: the extraction — one workshop module, discovered, three faces

**A new module, `tesseraql-studio-runtime`, carries everything Studio-shaped that lives in the
runtime module today**, and `tesseraql-camel-runtime` drops its compile dependencies on
`tesseraql-studio` and `tesseraql-test-core`. What moves: `StudioProviders`, `DocsProviders`,
`StudioRouteBuilder`, `StudioTestService`, `StudioScaffoldService`, `StudioDataService`, the
Copilot wiring, `StudioDocCache`, and the new shell/workshop machinery of structural
decision 2. `StudioAccess` does not move — it retires (structural decision 4).

**The discovery mechanism is the `RuntimeExtension` SPI** (design ch. 47) — the exact shape
`tesseraql-oidc`/`-saml`/`-scim` already use: the runtime carries no compile-time dependency
on the feature; the jar on the classpath plus the topology decide. One extension, three faces
by topology, which is the organizing sentence of the whole module:

| Boot | The extension installs |
| --- | --- |
| unhosted (no `HostContext`) | the full local Studio — app mount, providers, JSON API — today's behavior verbatim |
| hosted member, workshop on | the workshop data API (`/_tesseraql/studio/data/*`) and nothing visible |
| surface runtime, workshop on | the shell — app mount, `studio.shell.*` delegating providers, proxies |
| any boot, workshop off under a host | nothing |

**The seams this forces are deliberate, and small against the mass that moves:**

- **`RouteReloader` stays in the runtime module** (`--watch` owns it too) and loses its two
  Studio fields; it gains a reload-listener seam the extension registers (`studio.reload()`
  plus doc-cache invalidation). The runtime keeps one honest hook instead of two typed
  references.
- **`ExtensionContext` reaches most of `Deps`' twenty objects through the registry already**
  (`bean(name, type)`); what is not yet bound gets bound under a named key rather than the
  context growing Studio-shaped fields. The slice measures the exact list; the rule is that
  the runtime publishes seams, not Studio types.
- **The workshop flag travels as topology**: `HostContext` gains the workshop marker (computed
  by the host from DevMode plus the stack's shape, structural decision 1), the runtime binds
  it as a bean beside `STACK_MEMBER_BEAN`, and the extension's three faces key on the beans it
  finds — the same one-bean-serves-fence-bounce-and-chrome pattern #870 established.
- **The extension module depends on `tesseraql-camel-runtime`.** That is allowed and correct:
  it is runtime-side machinery, like the CLI. The boundary that must hold is the other one —
  `tesseraql-studio` (the surface module) still depends on no runtime, and does.

**What this buys, stated honestly.** The runtime module's dependency graph loses the test
framework, the SMTP test double and JUnit 4 — runtime-footprint problem 2 dies at the link
that was wrong. The deployment *image* still carries the jars until runtime-footprint
Decision 1 splits the distributions, because the image copies the CLI's dependency set; what
this extraction changes is that the split becomes a packaging decision instead of a
refactoring campaign, and the jars it would drop are inert under a host by topology, not by
configuration. The boundary guard runtime-footprint's open question 5 asks for also becomes
writable: a test asserting the runtime module's resolved compile scope names no studio and no
test-core artifact.

**Rejected: `optional` scoping inside the runtime module** — it keeps the classes and the
reflective wiring in the module whose graph is the complaint, and decays silently (the
footprint doc's own worry). **Rejected: moving the machinery into `tesseraql-studio`** — the
surface module would then need the runtime, reversing the Decision 15 boundary that holds
today.

## Structural decision 4: the atom replaces the allow-list, and the master switch becomes topology

**`tql.studio.edit.<name>` is the one Studio authority, checked at two points, and the second
is the real one.** The shell's switcher and pages filter by it (reach); the member's workshop
API refuses a principal without it on every route (authority) — `Atoms.holds` with the family
wildcard honoured, the same code path as every other atom. Service callers meet the same
check, because a principal is a principal.

**What retires, all pre-1.0 clean breaks:**

- **`tesseraql.studio.editRoles` and `StudioAccess`**, with `TQL-STUDIO-4031` — the last
  framework surface reading role names. Per-application authority, which the global allow-list
  never was, is exactly what the atom adds.
- **`tesseraql.studio.readOnly`.** Its two jobs are now done by better owners: per-caller
  write authority is the atom (deny-by-default — no grant, no edit, nothing to switch off);
  per-deployment safety is topology (a host mounts no Studio at all, structural decision 1).
  A master brake that survives both would be a key whose meaning changes by topology — the
  shape this campaign keeps deleting.
- **`StudioService`'s second-layer `readOnly` re-checks** become the workshop-activation
  invariant: the service is constructed writable exactly where the workshop is on. The
  path-traversal guard stays, obviously.

**What stays configuration: capability, not authority.** `testRunner.enabled`,
`scaffold.enabled`, `dataBrowser.enabled`/`.edit.enabled`, `confirmApply`, and the `copilot.*`
keys describe what this workshop *can* do — they lose their `!readOnly` conjunct and keep
their own defaults. `tesseraql.studio.enabled` keeps exactly one meaning, the unhosted boot's
mount switch, since hosted topology no longer reads it.

**One atom, no view verb.** Reading source, drafts and docs is the editing workflow's own
context, and the personas that need read-only access to *production* need a different surface
(open question 2), not a weaker grant to the workshop. The stack-shells guard stands: a verb
arrives only with the surface that checks it, and no read-only Studio surface arrives here.
The atom's wildcard serves the dev bootstrap: `--admin-permissions` gains
`tql.studio.edit.*`, so the development loop stays frictionless while production seeds narrow
bundles.

**The unhosted boot checks the same atom.** Existing Studio fixtures grant it to their
principals — the slice-2 fixture sweep, the same shape #870's `tql.app.use` fallout took.

## Slices

Three PRs, each independently green and observable:

| # | Slice | Contents | End state |
| --- | --- | --- | --- |
| 1 | The extraction | `tesseraql-studio-runtime` module; `StudioProviders`/`DocsProviders`/`StudioRouteBuilder`/`StudioTestService`/scaffold+data services/Copilot wiring/`StudioDocCache` move; `RouteReloader` listener seam; registry seams for `Deps`; runtime pom drops `tesseraql-studio` + `tesseraql-test-core`; compile-scope boundary test | Behavior-neutral: every existing Studio test passes unhosted; the runtime module's tree names no test framework |
| 2 | The atom | Workshop routes and providers check `tql.studio.edit.<name>`; `StudioAccess`/`editRoles`/`readOnly`/`TQL-STUDIO-4031` retire; capability keys lose the `!readOnly` conjunct; `--admin-permissions` default gains `tql.studio.edit.*`; fixtures gain grants; reference regen + CHANGELOG | Who may edit is a grant, per application; no framework surface reads roles |
| 3 | The shell | `studio` joins the topology skips; the workshop flag through `HostContext` + beans; surface mounts the app in dev; `{member}` route tree + `shell.base` href sweep; `studio.shell.*` delegation + the export table + workshop data API; Copilot SSE + download proxies; switcher; `_system.studioHref` origin-absolute; docs sweep | One Studio per stack in `dev`, none anywhere in production; the member fence has nothing Studio-shaped left to guard |

Order is 1 → 2 → 3: slice 2 wants the extension as the home of the check it rewrites; slice 3
wants both. Slice 3's route-tree move is the bulk of its diff and is mechanical; it stays one
slice because splitting it would ship a switcher whose pages still answer at retired
addresses.

## Guards

- **A framework surface checks atoms, never roles.** The last violation retires in slice 2;
  pinned by the slice's tests, and nothing new reads `principal.roles` in the workshop.
- **Authorization never moves to the shell.** Every delegated call carries the caller's own
  credentials and the member re-checks the atom; a shell bug can widen what is listed, never
  what is answered. Pinned by the forged-call test, not by review.
- **Deny-by-default.** No `tql.studio.edit` atoms → an empty switcher and a refused workshop
  API, browser and bearer alike.
- **The workshop is topology, not preference.** No configuration mounts Studio under a
  non-dev host — member or surface — and a `dev` stack over a catalogue gets no workshop
  either. Pinned by the host-without-DevMode test.
- **The export table is the only door.** A provider is reachable through the workshop API
  only by its explicit table row; there is no generic dispatch. New providers are unreachable
  until enumerated.
- **The runtime module's compile scope names no studio and no test-core artifact** — the
  boundary test from slice 1, which is runtime-footprint open question 5 made real.

## Test plan

**Slice 1**
- The whole existing Studio and docs test surface passes unhosted with the extension
  discovered from the classpath — the behavior-neutrality claim, not a sample of it.
- Apply still reloads: the listener seam carries `studio.reload()` + doc-cache invalidation
  (the `RouteReloader` tests keep passing; one new test pins the listener).
- The boundary test: the runtime module's resolved compile scope contains no
  `tesseraql-studio`, `tesseraql-test-core`, `greenmail` or `junit:junit`.

**Slice 2**
- A principal granted `tql.studio.edit.<app>` edits, applies, scaffolds and authors a
  migration; the same principal without the grant is refused on each — through the JSON API
  and the pages both; the wildcard grant passes; a JWT service caller meets the same check.
- `editRoles` fixtures are gone; no test grants a role to reach Studio.
- The retired keys are absent from the regenerated configuration reference.

**Slice 3**
- The headline IT, in the `MultiAppGatewayIntegrationTest` arrangement with DevMode: two
  members, a principal granted `tql.studio.edit.a` only — the switcher lists `a` alone, `b`'s
  workshop pages answer 404-shaped through the shell, and an edit→apply on `a` through the
  shell changes what `a`'s route answers (the reload observed end-to-end).
- Authorization stays at the member: a forged direct call to `b`'s internal workshop port
  with `a`'s principal is refused by `b`'s own check — assert the member's refusal.
- A `host` stack (no DevMode) serves no `/_tesseraql/studio` at the origin, no workshop API on
  any member, and no member-mounted Studio app; a `dev` stack over an install root likewise.
- The unhosted boot still serves its full local Studio (the existing tests, re-run as the
  pin).
- Copilot streams frame-by-frame through the shell; a data export and a docs share link round
  trip through the proxy; run-tests completes within its own bound on a suite slower than the
  page timeout.
- Degradation: stop one member, the switcher's card marks it unreachable, HTTP 200.
- The actor: a delegated apply records the forwarded principal in the member's audit trail.
- `_system`: a member page under dev links `/_tesseraql/studio` origin-absolute; under a
  non-dev host it renders no Studio link.

## What moves in the docs, and when

With the code PRs, not before: `studio.md` (addresses gain the switcher and `{member}` shape;
the permission section becomes the atom; the `readOnly`/`editRoles` rows leave the config
table; a topology paragraph says where Studio exists and why production is not it),
`hosting.md` (one sentence: a hosted stack carries no Studio; the workshop is `dev`'s),
`troubleshooting.md` (the `TQL-STUDIO-4031` row retires), `getting-started.md` and
`vscode-extension.md` wherever member-prefixed Studio URLs appear (verified at slice time),
`deployment.md`'s `--admin-permissions` example (gains `tql.studio.edit.*`), the error and
configuration references (regenerated: retired keys and codes out, the new member-unreachable
and workshop-refusal codes in), `runtime-footprint.md` Decision 3 (shipped-status note),
`stack-architecture.md` Decision 14 + the slice-8 paragraph (shipped-status note),
`stack-shells.md` (its slice-8 reservation gains the pointer here; the `editRoles` retirement
sentence gets its arrival note), `CHANGELOG.md` per slice — breaking-change lines for the
retired keys and the mount moves, no migration steps.

## Deliberately not in this design

- **A production documentation surface.** The read-only Studio production members carry today
  disappears with the mounts; a docs surface for deployed stacks is a real candidate feature
  with its own audience, no write machinery and its own design — not a mode of the workshop.
- **A `tql.studio.view` atom.** No surface checks it (see above); minting it now would violate
  the no-atoms-without-a-surface guard this campaign wrote.
- **The distribution split** (runtime-footprint Decision 1). This design makes it a packaging
  decision; taking it stays that document's move.
- **MCP dev tooling.** `McpDevTools` runs in the CLI over app homes and is indifferent to all
  of this.
- **A remote workshop.** Delegation is loopback; `dev` is one machine; the `vscode://` links
  assume it and keep assuming it.
- **Editing the surface runtime's own portal app.** The shell edits members; the portal's
  extracted tree stays nobody's workshop — the reason its config states today survives as
  topology.
- **Cross-member aggregate Studio views.** The switcher and per-member workshops, nothing
  else — the standing exclusion, applied to Studio.

## Open questions

Each gated on the slice it blocks, with a recommendation:

1. **Does `readOnly` retire outright, or survive as an unhosted-only brake?** — *gates
   slice 2.* Recommended: retire. The unhosted boot is deny-by-default under the atom
   already; a key that means something only on one topology is the two-meanings shape, and
   embedders wanting a read-only docs view are open question 2's audience, not this key's.
2. **Is losing the production members' read-only Studio acceptable?** — *gates slice 3.*
   Recommended: yes, and record the cost in `studio.md`. The alternative — members keep a
   read-only Studio mount under a host — keeps the studio jars load-bearing in every
   deployment, reopens the two-doors shape the shells campaign exists to close, and serves an
   audience a purpose-built docs surface would serve better.
3. **Workshop scope: DevMode ∧ source-trees, or DevMode alone?** — *gates slice 3.*
   Recommended: both facts, as designed — `dev --stack <install-root>` runs packages, and
   editing a package is the hazard regardless of the verb. The flag is one boolean the host
   computes; the second conjunct costs nothing.
4. **Copilot through the shell: proxy the member's stream, or run Copilot on the surface?** —
   *gates slice 3.* Recommended: proxy. Copilot's subject is the member's drafts and source;
   moving the service to the surface would give it no app home to read and would put the one
   LLM credential where every member's secrets are not.
5. **The module's name** — *gates slice 1's pom only.* Recommended: `tesseraql-studio-runtime`
   — the `-camel-runtime` naming line, and the jar says which side of the boundary it is on.
   Alternative considered: `tesseraql-workshop`, rejected as a second word for a surface every
   document already calls Studio.
