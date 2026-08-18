# Deploy replaces the runtime

Implementation design for [stack-architecture.md](stack-architecture.md) Decision 29: deploying
an application replaces its runtime, not the stack. Decision 29 records the requirement (stated
in review: deploying one application must not affect the others), the measurement that today's
behaviour is the requirement's negation, and the boundary (the gateway, the framework schema,
`tesseraql-stack.yml`'s values and the process itself stay stack-scoped — replacing those *is*
deploying the stack). This document records how the replace is built: the three structural
decisions, the code they touch, the slices, the guards, the tests, and what is deliberately left
out. Written 2026-08-18, before implementation.

Everything below was measured against main at #860 unless marked otherwise.

**Status 2026-08-18: all three slices shipped.** Slice 1 (the host operation, the live relay,
the stack's own graceful stop); slice 2 (the reconciler, atomic state writes, the status
write-back); slice 3 (the `deploy` verb, TQL-UPGRADE-4092, the docs sweep). Open questions 1–4
each closed on their recommendation (the file-state trigger, the declared drain bound with the
cooperative stop requested at drain start, the single `deploy` verb, the ready probe before the
swap); question 5 closed as deferred, its section standing as the record the grants work
inherits. Review itself reshaped the design five times before approval — the swap race, the
stack's own stop, the overlap window's fine print, the job drain correction, and the
deploy-authorization boundary — each recorded in place. One count corrected at implementation:
structural decision 3 said the verb count moves from 25 to 26; the reference stood at 24, so it
moves to 25. And one correction found in production CI after shipping: the swap race has a
second leg — the retiring runtime's suspend gate answers 503 on an established connection —
closed in the swap-race section above.

## What exists today, measured

**`MultiAppHost` has no replace operation, and its state is immutable.** The host's fields are
`Map.copyOf`/`Set.copyOf` snapshots taken at start (`MultiAppHost.java:228`): runtimes by slot,
member names, canary weights. Its public surface is start, lookup, the canary accessors, and
close. Shipping a new version of one application today is a full-host restart, which restarts
every application in the stack.

**The canary machinery is a replace with a ramp — but only at boot.** `MultiAppHost.start` asks
`AppUpgrader.canary(name, installRoot)` per member and, when a candidate is staged, starts a
second runtime for the same application in slot `<name>#canary`, at the same base path, on an
ephemeral port (`MultiAppHost.java:193-203`). The gateway resolves the target port **per
request** — `MultiAppGateway.targetPort` rolls `ThreadLocalRandom` against the staged weight
(`MultiAppGateway.java:306-314`) — so *switching targets is already a live operation*; what is
not live is everything around it. `AppUpgrader.setCanaryWeight` writes a state file the running
host never re-reads (the weights map is a start-time copy), so adjusting a canary's traffic
share today means restarting the whole stack, which defeats the canary.

**The upgrade lifecycle is a library with no owner.** `AppUpgrader` carries the whole lifecycle —
`preflight` (version must be newer, framework range must include the running version), `upgrade`
(direct, or `canary` staging), `setCanaryWeight`, `promote`, `rollback`, with sha256 verification
overloads — and its only production caller is `MultiAppHost.start` reading canary state. There
is no CLI verb for any of it, and `hosting.md` says so as a published stance: "There is no
`install` verb on the CLI today: a deployment either ships the directory or drives `AppInstaller`
from its own tooling." (The stance is about *fetching* — "getting bytes onto a host is a
deployment concern" — and structural decision 3 below is careful not to reverse it.)

**Install is already side-by-side, and the catalogue is the switch.** `AppInstaller.place`
extracts to `<installRoot>/<name>/<version>` (`AppInstaller.java:87`); only a same-version
re-place deletes anything, and `preflight` refuses non-newer versions anyway. The catalogue
records the *active* version: `AppCatalog.register` refuses a held name (TQL-APP-4213, Decision
23), and `replace()` is the explicit act an upgrade, promote or rollback performs. The upgrade
state beside it — `.upgrade/<name>.json`, an `UpgradeState(previous, candidate, canaryWeight)` —
is written with a plain `Files.write` (`AppUpgrader.java:170-178`), fine while the host reads it
once at boot, not fine once a running host reads it concurrently. `runtime-footprint.md` open
question 4 already wants install-beside-and-switch-a-pointer for Windows' held-file problem;
this design's switch *is* that pointer.

**Each runtime already knows how to drain.** `TesseraqlRuntime.close` stops the Camel context
first, and the shutdown strategy is configured per application: `tesseraql.shutdown.timeout`
(default 45s) with `tesseraql.shutdown.forceOnTimeout` (default true) — a declared, deliberate
bound on in-flight work (`TesseraqlRuntime.java:2103-2115`, docs/audit-hardening.md Decision 6).
Close order after the drain: job executor, telemetry, lanes, tenant pools, pools, then the
module loader (`TesseraqlRuntime.java:2928-2967`) — Decision 28 gave the loader that lifecycle
naming Decision 29's replace as the reason. The prerequisite is shipped: a retiring runtime
closes everything it owns and clobbers nothing shared.

**Each runtime already answers whether it is ready.** `/_tesseraql/health/live` is pure liveness;
`/_tesseraql/health/ready` runs the full roll-up including the datasource probe
(`OperationsRouteBuilder.java:147-154`), on the runtime's internal port, reachable by the host
without going through the gateway.

**Sign-in survives a replace with no work, and the guard that keeps it true is shipped.**
Sessions live in the framework datasource's JDBC store; the hoist (#844) made hosted runtimes
*validate* the `security` schema instead of migrating it, refusing on mismatch (TQL-APP-4214,
`FrameworkMigrations.java:37-46`). The canary integration test already documents the property
from the other side: its fixture comment records that without the shared framework connection a
session signed in on stable would die on the canary leg.

**The relay's per-app state is a start-time snapshot too.** `StackRelay` holds `Map.copyOf`
snapshots of the member entries and each member's ingress-strip set
(`StackRelay.java:139-146`), and caches one `HttpProxy` per internal port, never evicted
(`StackRelay.java:122`). The port lookup is the one thing already live (a function, consulted
per request). The strip set is read from the member's *config* at gateway start
(`MultiAppGateway.ingressStripHeaders`) — a new version can change it.

**During a canary, one application is briefly a two-node cluster, and that is already handled.**
Stable and candidate share the business datasource in production; job claims and outbox claim
scoping are keyed to arbitrate exactly this (they exist for multi-node deployments), and
`deployment.md` already names the schema discipline the window demands: "expand/contract
(backward compatible) — the same discipline the canary flow already requires."

## Structural decision 1: replace is the canary lifecycle with the ramp collapsed

The host gains one operation set, and the direct replace is a degenerate canary rather than a
second mechanism. `MultiAppHost`'s runtime map becomes live state — each member slot holds the
`InstalledApp` entry it was started from beside its runtime, so the host knows *which version*
each slot runs, which is what a reconciliation has to diff against:

- **`replace(entry)`** — the whole move, for a direct deploy or a post-promote rollback: run the
  candidate's admission checks (below), start the new version's runtime on an ephemeral port at
  the member's unchanged base path (exactly the canary-slot arrangement today), probe its
  `/_tesseraql/health/ready` once, swap the member's slot so the per-request port lookup answers
  with the new runtime, then drain and close the old one. Any failure before the swap — an
  exception out of `TesseraqlRuntime.start` (which is where TQL-APP-4214 surfaces), a refused
  admission check, a failed probe — abandons the candidate and leaves the old runtime serving,
  untouched. **A failed replace is a no-op**; that is the headline property and the headline
  test.
- **`stageCanary(entry, weight)` / `setCanaryWeight(name, weight)` / `discardCanary(name)`** —
  the ramp, now live: start the candidate beside the stable slot (admission checks and ready
  probe included), adjust the weight the per-request roll reads, or drain and close the
  candidate with the catalogue untouched. This also fixes the measured defect above: a weight
  adjustment reaches the running host instead of requiring a stack restart.
- **`promoteCanary(name)`** — the candidate runtime *becomes* the stable slot and the old stable
  drains and closes. Nothing starts; the promoted runtime has been serving its weight share
  already, which is the strongest health check available. Rollback of an unpromoted canary is
  `discardCanary`; rollback after promote is `replace` with the previous version's entry — the
  files are still on disk, side-by-side install is what makes that a plain replace.

**Admission checks are the start-time guards, re-run for the candidate.** A replace admits a new
version into a running stack, so the checks the stack ran at boot run again, scoped to one
application: the modules guard (declared-but-unresolved refuses with TQL-APP-4216, lock
disagreement with TQL-APP-4217 — a deploy of a version with new modules needs its
`tesseraql modules resolve` first, exactly as at boot), and Decision 22's framework guards (a
candidate that newly declares `tesseraql.framework.datasource` while the stack supplies the
connection is refused with TQL-APP-4212; with no stack supply, a candidate whose resolved
coordinate disagrees with the running agreement is refused with TQL-APP-4211's comparison). Same
codes, same messages, same meaning — refused at admission instead of at boot.

**Migrations sequence the way the guards already say.** The candidate's runtime start migrates
its own business schema (per-application history, Decision 25's naming fix) *before* any traffic
moves — so the old version serves over the migrated schema for the length of the window, which
is the expand/contract discipline `deployment.md` already teaches, now stated as the deploy
contract rather than a canary aside. The framework `security` schema is the opposite by design:
validate-don't-migrate means a candidate expecting a newer framework schema is **refused**
(TQL-APP-4214) and the operator migrates the stack first — "deploy one application" never
quietly re-migrates a schema every neighbour is standing on.

**The overlap window opens at candidate start, not at the swap — said plainly, because it is
where "is the old version safe until the new one takes over?" has its fine print.** The old
runtime serves every request until the swap, and the candidate takes no HTTP traffic before it
(none for a direct replace, its weight share for a canary). But a runtime that starts is a whole
runtime: the candidate's job executor, pollers and outbox begin working at its start, claim-
arbitrated against the old version's — **the canary weight gates HTTP only**. That is today's
canary semantics and the multi-node semantics (a node that joins a cluster works before it takes
front traffic), inherited rather than invented, and the claim machinery is the arbiter either
way — but an operator staging a 10% canary should read that its *background* participation is
not 10%, so `hosting.md`'s deploy section says it. And the no-op property carries the same fine
print, stated rather than implied: **a failed replace is a topological no-op** — the old runtime
never stopped, the catalogue never moved — while the candidate's business migrations that
already ran stay ran (Flyway does not undo; expand/contract is precisely what makes a
v2-migrated schema safe under a still-serving v1), and background work its brief life performed
is real work, exactly as if a node had joined and left.

**The drain is the one the application already declared.** The swap happens first, so new
requests reach the new runtime; the old runtime then gets `close()`, whose Camel shutdown
strategy drains in-flight exchanges under the application's own `tesseraql.shutdown.timeout` and
force-stops at the bound it declared. Long-lived streams — SSE, exports — are cut at the force
timeout; their clients reconnect and land on the new version. No second drain knob is
introduced: the timeout is part of the application's declaration (Decision 26's classification),
and a stack-level override would be two numbers for one bound.

**A running job is drained by asking, not only by waiting — a correction, found in review.**
An earlier draft of this paragraph said in-flight job runs "stop cooperatively, as on any node
shutdown"; measured, they do not. Scheduled runs execute on Camel exchanges
(`SchedulingRouteBuilder.java:84-91`), so the drain *waits* for them — but the cooperative stop
the batch platform ships (`repository.requestCancel`, polled at step and chunk boundaries, a
committed checkpoint the rerun resumes exactly from) is wired to the operator's cancel action
**only** (`OperationsRouteBuilder.java:429`); `JobExecutor.close()` stops the heartbeat thread
and requests nothing. So today a run longer than the drain bound is force-cut: an exception and
a failure alert, or a stranded RUNNING row the reaper later marks abandoned — recovery
machinery, on a path where the graceful machinery already exists unused. The design wires it:
**at drain start, the retiring runtime requests the cooperative stop for every execution it
owns**, with a stop reason naming the deploy rather than "stopped by operator". A run between
steps stops before the next one; a chunk step stops at its next committed checkpoint — real
counts, an exact resume point — comfortably inside a bound that would otherwise force-cut it;
a run in its final step simply completes. The rerun that resumes from the checkpoint goes
through the existing rerun path (and lands on the new version), deliberately not automatic:
which business date to rerun and whether tonight's window still wants it is the operator's
call, and the status the stop recorded is what they decide from. Force-cut at the bound and
the reaper stay, unchanged, as the last resort for a run that ignores the flag. The heartbeat's
close ordering already supports all of this — it outlives the Camel drain precisely so a run
winding down during it keeps saying so. The same request-then-drain applies on the stack's own
stop (the section below), because it is the same `close()`.

**The swap leaves one race, closed rather than tolerated** (asked in review: is the handling
graceful end to end?). The relay resolves the target port per request, so a request that
resolved the old port just before the swap can reach the old runtime after its consumer
suspended — and would surface as a 502 minted by the deploy itself, on a path where every other
step was built not to drop anything. The relay therefore re-resolves the route and retries
**once**, and **only when the connection was never established**: no byte reached the origin, so
nothing can double — a `POST` whose connection died mid-flight is *not* retried, because
replaying a request the origin may have acted on is a worse defect than the 502 it saves. The
headline test below fires requests continuously through the swap precisely so this window is
observed rather than reasoned about.

**Correction, found by that same headline test on CI after slice 1 shipped: the race has a
second leg.** A retiring runtime's socket keeps accepting while its Camel consumer is
suspended, and the suspend gate answers **503** before any route runs — a connection that *was*
established, so the never-connected retry does not fire, and the deploy mints a 503 instead of
a 502 (observed as exactly one `503` sample at the swap boundary in #865's first CI run). The
fix keeps the retry's shape and adds the discriminator the leg needs: on a 503, the relay asks
whether the slot has **moved away from the port that answered** — an application's own 503 (a
lane at capacity, a readiness roll-up) comes from the port the slot still names and passes
through untouched — and retries once, for **bodyless requests only**: a request body is a
stream the first send already consumed, so a bodied request keeps the 503 and its client
resubmits, on a window a few milliseconds wide. The ordering that makes the discriminator
sound is the design's own: the swap precedes the drain, so by the time the suspend gate can
answer, the slot already names the new runtime.

**One wrinkle, named rather than hidden:** a declared `server.port` (Decision 4a's fixed
*internal* port) is honoured at stack start, but a replaced runtime answers on an ephemeral port
— the old runtime holds the declared one until it closes, which is after the new one binds. The
port moves back at the next stack start. Internal ports are the relay's concern and the relay
follows the slot; the declared port exists for reaching an application *beside* the gateway,
and that convenience going ephemeral between a replace and the next stack deploy is recorded in
`hosting.md` rather than engineered around (a double restart to reclaim the number would trade
a real property — no gap — for a cosmetic one).

**The relay's snapshots become as live as its port lookup.** The per-app entry map and the
ingress-strip map turn into lookups backed by state the host maintains (the shape `portOf`
already has), so a replaced version's changed `forwardedHeader` takes effect with the swap; the
proxy cache gains eviction for retired ports. Membership does not change — same name, same
address — so nothing about routing or the origin fence moves.

**Rejected: stop-then-start in place.** Restarting the member's runtime in its slot needs no
swap machinery — and has a window with *nothing* serving, and a failed new version leaves the
member down, which is the requirement's negation concentrated on one application. The
requirement is "deploying must not affect" — an outage window fails it for the deployed
application itself. **Rejected: taking over the old port** (SO_REUSEPORT and friends): platform
lore to save the relay a map update it already does per request. **Rejected: a second gateway
and a whole-stack blue/green**: that is deploying the stack, which exists, is Decision 29's
boundary, and needs no design.

## The stack's own stop, measured — and brought inside the design

Asked in review: the application's replace is graceful — is the stack's own stop? Measured, it
is not, twice over, and Decision 29's own exclusions depend on it being so: "a fleet deploys by
rolling node replacement" is only graceful if replacing a *node* is, and the deployment image's
`CMD` is `tesseraql host --stack /stack`.

- **`host` never drains at all.** `HostCommand` holds the process open with
  `Thread.currentThread().join()` inside try-with-resources and registers **no shutdown hook**
  (`HostCommand.java:80`) — on SIGTERM the JVM runs hooks and exits; a parked thread's
  try-with-resources never runs, so `gateway.close()` is never called. Every runtime's drain
  machinery — the declared `tesseraql.shutdown.timeout`, the close ordering Decision 28
  finished — is reachable only through `close()`, and nothing on the production signal path
  calls it. Every container stop is a hard kill. `dev` *does* register the hook
  (`TesseraqlCli.java:247`) — the asymmetry points the wrong way.
- **Even when `close()` runs, the front cuts in-flight work before the runtimes drain.**
  `MultiAppGateway.close()` closes the front server first, and Vert.x 4.5's `HttpServer` has
  `close()` only — no grace-period `shutdown()` (that is Vert.x 5; verified against 4.5.31) —
  so open front connections are cut, and the runtimes then dutifully drain exchanges whose
  callers are already gone.

The repair is small and belongs to slice 1, because it is the same drain story:

- **`host` registers the shutdown hook** `dev` already has. One asymmetry, deleted.
- **The gateway's close becomes an ordered drain.** First the relay's own readiness answer
  flips to 503 while liveness stays 200 (the orchestrator's contract: "stop routing to me, do
  not kill me") — the health pair is already the relay's own answer, so this is a flag, not a
  feature. The relay keeps serving *everything else*, new requests included, while its
  in-flight count drains to zero under a bound **derived** from the members' own declared
  `tesseraql.shutdown.timeout`s (their maximum — no new knob; the stack's stop cannot need
  longer than its slowest member is allowed to take). Then the front closes, the runtimes drain
  under their own bounds as today, and the client, Vert.x and the framework pool follow in the
  existing order. Long-lived streams are in-flight requests that never end; the derived bound
  cuts them, which is the same deliberate boundary as the replace's.
- **The docs owe the orchestrator one sentence**: the platform's grace period
  (`terminationGracePeriodSeconds` and kin) must exceed the derived bound, or the platform's
  SIGKILL wins — stated in `deployment.md` where the image is taught.

A single-node stack stopping is downtime by definition; graceful here means nothing in flight
is cut before the bound, on the replace path and the stop path alike — one drain contract,
observed from two directions.

## Structural decision 2: the trigger is the state on disk, and the host reconciles

Decision 29 left the trigger open across three candidates — an ops-console action, an `install`
that notifies a running host, a catalogue watch. The choice is the third, stated precisely: **the
install root's state is already the protocol** — `catalog.json` names each member's active
version, `.upgrade/<name>.json` names a staged candidate and its weight, and `MultiAppHost.start`
already builds its whole hosting arrangement, canaries included, by reading them. A host restart
mid-canary already "works" today because boot is a reconciliation. The design makes that
continuous: **a running host converges to the same function of the same files that boot
computes.** One protocol, two read points, no new channel.

A new **`StackReconciler`** (tesseraql-camel-runtime, owned by the gateway — the gateway owns
both the host and the relay, and both change on a replace) watches the install root: a
`WatchService` on the root (for `catalog.json`) and `.upgrade/`, events debounced onto **one
serialized reconciler thread** — starting runtimes is heavy and blocking, and two concurrent
replaces of one member is a race nobody needs. Each pass reads the catalogue and upgrade state
fresh and diffs against the live slots:

- catalogue version ≠ stable slot's version, and the canary slot runs exactly the catalogued
  version → **`promoteCanary`** (this is what a `promote` on disk looks like: catalogue moved to
  the candidate, state cleared);
- catalogue version ≠ stable slot's version otherwise → **`replace`** with the catalogued entry
  (a direct upgrade — and a post-promote rollback, which is just the catalogue moving back);
- a staged candidate with no canary slot → **`stageCanary`**; a canary slot with no staged
  candidate → **`discardCanary`**; a weight that moved → **`setCanaryWeight`**.

Every rule is idempotent and the pass converges, so duplicate watch events, a promote's two file
writes arriving as two events, and a pass racing a write all resolve to "read again, diff again,
nothing to do". **Failure does not loop:** the reconciler acts on events, not on a schedule — a
candidate that failed admission stays failed, recorded, until the operator writes something new.

**The host reports back through a file it alone writes.** The reconciler records each attempt's
outcome — applied, or refused with the refusal's own message — in
`.upgrade/<name>.status.json`. One file, one writer: the CLI writes intent
(`.upgrade/<name>.json`, the catalogue), the host writes outcome, and neither ever writes the
other's file. This is the async-deploy observability answer: the CLI's `--wait` (decision 3)
tails the status file instead of inventing a control connection.

**`AppUpgrader`'s state writes become atomic** (write-temp-then-`ATOMIC_MOVE`) — required the
moment a second process reads them concurrently, cheap, and it removes the torn-read case from
the reconciler entirely (a malformed read logs and waits for the next event regardless, as the
last line of defence).

**Scope: the reconciler exists only where a catalogue does.** An install root is the deployment
shape with versions and a ledger; a workspace of source trees (hosted since #832) synthesises
its entries from disk and keeps restart-to-deploy; `dev` has no catalogue and has `--watch`,
which is a different loop (routes, not versions). And **membership stays start-time on
purpose**: the 4211 agreement, the portal's member list (`HostContext.stackMembers`), and
`root.redirect` validation are all resolved against the membership at start. A new name in the
catalogue, or one removed, is the stack changing shape — a stack deploy, Decision 29's stated
boundary. The reconciler diffs *versions of members it started with* and ignores membership
edits (logging that it did, so the operator learns the restart is owed).

**Rejected: an authenticated notify endpoint** (on the gateway or the surface runtime). The
relay is I/O-free by design (Decision 13; the portal could not be gateway content for the same
reason), so it would land on the surface runtime as an ops action — whose authorization
semantics (`ops.app.<name>`) are exactly open question 4, deliberately unresolved until slice 7.
*(Since resolved: docs/stack-shells.md's atoms — `tql.ops.view.<name>`/`tql.ops.run.<name>`, with
`tql.app.deploy.<name>` for this endpoint, arriving in that design's slice 3.)*
A control plane whose auth model is pending is not a foundation; the filesystem's permission
model is the same trust boundary `host --stack` already runs under. **Rejected: the ops-console
action as the mechanism** — same reason, and it is not lost: an ops-console deploy page, when
slice 7's grants exist, *writes the same files* and rides the same reconciler. Choosing the file
protocol first is what keeps the later UI from becoming a second mechanism. **Rejected:
polling** — a watch with event debounce is the same code with better latency and no idle churn;
boot remains the fallback read point for anything a dead host missed.

## Structural decision 3: the operator's pen is a `deploy` verb

The lifecycle library has no owner (measured above), and a file protocol with no pen is not an
interface. A new CLI verb writes the install root's state through `AppUpgrader` — thin by
construction, because the library already does everything:

```sh
tesseraql deploy ./orders-2.1.0.tqlapp --stack /opt/tesseraql/apps            # direct replace
tesseraql deploy ./orders-2.1.0.tqlapp --stack ... --canary --weight 10      # stage a canary
tesseraql deploy weight orders 50 --stack ...                                 # move the ramp
tesseraql deploy promote orders --stack ...                                   # candidate goes active
tesseraql deploy rollback orders --stack ...                                  # canary or last upgrade
tesseraql deploy status [orders] --stack ...                                  # read back both files
```

One command, a positional package for the deploy itself, subcommands for the lifecycle verbs the
library already names. `--sha256 <hex>` rides the existing `verifyIntegrity` overloads.
`--wait` (on deploy and promote) tails `.upgrade/<name>.status.json` until the host reports the
outcome, so a CI pipeline gets a synchronous exit code out of an asynchronous host — and without
`--wait`, or with no host running at all, the command still *works*: the state is written, and
the next host start converges to it, which is the file protocol's whole point.

This does not reverse `hosting.md`'s no-fetcher stance: the package is a local path, and getting
bytes onto the host remains the deployment's concern. What changes in the text is the second
half of the sentence — "drives `AppUpgrader` from its own tooling" becomes "runs
`tesseraql deploy`", with the library still there for tooling that wants it. `preflight`'s
refusals (not newer, framework range) surface in the terminal before any state is written; a
`--stack` that is not an install root — no `catalog.json`, so no versions and no ledger to
deploy into — is refused with **TQL-UPGRADE-4092** naming what an install root is and that a
workspace of source trees deploys by restart.

The verb count moves from 24 to 25 (`cli-surface.md`'s mapping table gains one row; the
draft said 25 to 26 — corrected at implementation against `reference-cli.md`); `deploy` takes
`--stack` explicitly like `host` does — production does not guess (Decision 9's `host` rule
applies: the flag is required, no discovery).

## Per-application deploy authorization, asked in review

Stated as a requirement in review: a team should be able to deploy **only the applications it
manages**. The requirement is recorded here with its measurement, its target shape, and what
holds the line until that shape exists — because the file protocol alone cannot carry it.

**The measurement: install-root write access is deploy-everything authority.** `catalog.json`
is one file and `.upgrade/` one directory; POSIX permissions scope files, not entries, so no
arrangement of ownership expresses "may write app A's entry and not app B's". Per-application
scope therefore requires an authenticated principal and a policy check — a control plane with
identity — which is exactly the surface this design twice declined to build on, because its
authorization model (`ops.app.<name>`, the stack architecture's open question 4) is
deliberately unresolved until slice 7. *(Since resolved by docs/stack-shells.md, which is that
grants work: the authenticated deploy surface is its slice 3, on the `tql.app.deploy.<name>`
atom.)* The requirement does not break the file-first choice; it confirms it: the
authenticated surface, when it comes, **authorizes and then writes the same files**, and the
reconciler stays the one mechanism.

**The target shape**, sketched so the grants work knows deploy is one of its customers:

- The grant is per application in the shared store — `ops.app.<name>` gaining a *deploy*
  action, exact semantics owned by that grants question rather than invented here ahead of
  it. *(Honoured in substance, renamed by the model: the atom is `tql.app.deploy.<name>` —
  deploying is an authority over the application as a unit, not a row-scope modifier —
  docs/stack-shells.md structural decision 1.)*
- The surface is the stack surface runtime (it runs in the host's process with the install
  root on its filesystem, and it is where authenticated framework surfaces live by Decision
  24): an endpoint — and later an ops-console page — that authenticates the caller, checks
  the grant against the *package's declared name*, runs the preflight, and writes the intent
  files the reconciler already consumes. Upload of the `.tqlapp` rides the existing
  file-transfer machinery.
- The CLI grows a remote mode beside the local one — `deploy --url <origin>` with a bearer
  from `tesseraql token`, the exact dual shape `token` itself already has (`--app` local
  mint xor `--url` remote) — so a pipeline deploys with a scoped token instead of
  install-root access.

*(Arrived — docs/stack-shells.md slice 3: the endpoint, the `tql.app.deploy.<name>` atom
checked against the package's declared name, and `deploy --url` shipped as sketched; the
interim below is no longer the only line, though the repository boundary remains a fine one.)*

**Until then, the line is held where teams already differ: the repository and its pipeline.**
Decision 23 made the name the inter-team contract and the deployment a composition; a CD
arrangement in which each application's repository can trigger only its own deploy job — repo
permissions the organization already operates — enforces "only what I manage" at the boundary
that exists today, with `tesseraql deploy` as the job's tool. What the interim does *not*
give is per-app scoping for humans logged into the host machine: install-root access stays
root-equivalent for the stack, stated plainly in `hosting.md` rather than implied.

**And one boundary stated so the grant is not oversold when it arrives:** per-application
deploy authority is an operational guardrail — who may move which version where — not a
security isolation between mutually distrusting teams. A deployed application runs in the
stack's shared process, and stack-scoped configuration values and secret resolution are
visible to every member by design (Decision 26). Teams that must not be able to affect each
other do not share a stack; that is Decision 27's definition, not a limitation of this
design.

## Slices

Three PRs, each independently green and observable:

| # | Slice | Contents | End state |
| --- | --- | --- | --- |
| 1 | The host operation | Live slots holding entry+runtime; `replace`/`stageCanary`/`setCanaryWeight`/`promoteCanary`/`discardCanary` with admission checks, ready probe, swap-then-drain; relay live entry/strip lookups + proxy eviction + swap-race retry; the stack's own graceful stop (`host` shutdown hook, ordered gateway drain with the readiness flip and the derived bound) | The library-level replace exists and is proven under load; a stack stop drains instead of hard-killing; nothing calls the replace in production yet (the #831 `AppDirectory` precedent) |
| 2 | Reconciliation | `StackReconciler` (watch, debounce, serialized diff-and-act, status write-back); atomic state writes in `AppUpgrader`; membership-edit logging | A running host converges to the install root's state; boot and live are one function of the same files |
| 3 | The `deploy` verb + docs | `DeployCommand` (package, promote, rollback, weight, status, `--wait`, `--sha256`); TQL-UPGRADE-4092; `hosting.md` deploy section, `deployment.md`, `cli-surface.md` row, reference regen, CHANGELOG, Decision 29 shipped note | An operator deploys one application with one command and the stack never restarts |

Slice 1 must land first. Slice 3 is usable without 2 in the restart shape (write state, restart
converges — today's canary boot path), but `--wait` reads the status file slice 2 introduces, so
the natural order is 1 → 2 → 3; if review prefers, 3 can land before 2 with `--wait` deferred
into 2.

## Guards

- **A failed replace is a no-op** — the swap is the last step, everything before it can only
  abandon the candidate. Pinned by tests, not by review vigilance.
- **Admission re-runs the boot guards for the candidate**: TQL-APP-4216/4217 (modules),
  TQL-APP-4212/4211 (framework datasource, scoped to the candidate against the running
  agreement), TQL-APP-4214 (framework schema validate — surfaces from the candidate's own
  start). No new codes for any of these: same mistake, same message, different moment.
- **TQL-UPGRADE-4092** — `deploy` against a directory with no `catalog.json`: names what an
  install root is, and that a source-tree workspace deploys by restart. (Next free in the
  UPGRADE domain after 4090/4091; the APP 42xx run is at 4217.)
- **One file, one writer** — intent files (CLI) and the status file (host) have disjoint
  writers; state writes are atomic moves. The reconciler never writes intent, so a host bug
  cannot destroy an operator's staged deploy.
- **No retry loop** — the reconciler acts on events; a failed candidate stays failed and
  recorded until the operator acts. An event-driven failure is one loud line, not a hot loop of
  runtime starts against the same defect.

## Test plan

**Slice 1**
- The headline IT, extending `MultiAppCanaryIntegrationTest`'s arrangement: requests fired
  continuously through the gateway while `replace` runs — every response is a 200, the version
  marker flips stable→new, and none arrive after the swap from the old version. The property is
  Decision 29's requirement stated as an assertion.
- Sign-in survives: mint a session on v1 (framework store shared via the stack file, as the
  canary IT already arranges), replace, the same cookie authenticates on v2.
- A failed replace is a no-op: a candidate that fails admission (unresolved modules), fails
  start (framework schema ahead → 4214 — arranged the hoist-test way, by pre-migrating a newer
  framework schema than the candidate's runtime expects), or fails the ready probe leaves the
  old runtime serving and the host's state unchanged.
- Canary lifecycle live: stage, weight moves without restart (the measured defect's regression
  test), promote starts nothing (the candidate runtime's identity is the stable slot's after),
  discard drains the candidate only.
- Relay upkeep: retired port's proxy evicted; a candidate whose config changes
  `forwardedHeader` gets the new strip set after the swap.
- Drain: an in-flight slow request on the old runtime completes after the swap while new
  requests land on the new runtime (the lane-test polling shape from #860 — observe, don't
  guess timing).
- The swap race: a request routed to a port whose runtime is already draining is retried against
  the fresh lookup and answers 200 (a relay-level test with a stub origin that refuses the
  connection, the `SuiteRelay` seam's shape); a connection that dies mid-response is *not*
  retried and surfaces as the 502 it is.
- The job drain: a chunk-step run in flight when `replace` fires stops at a committed
  checkpoint with the deploy-named stop reason, its counts real and its rerun resuming exactly
  there (the batch platform's existing stopped-checkpoint tests extend to the deploy trigger);
  a firing that lands during the overlap is claimed exactly once between the two runtimes (the
  existing claim-key tests' arrangement); a run in its final step completes and records
  completion.
- The stack's stop: `gateway.close()` with an in-flight slow request — readiness answers 503
  during the drain while liveness stays 200, the slow request completes, a new request arriving
  mid-drain is served, and the close returns within the derived bound (gateway-level, the same
  observe-don't-guess shape). The `host` hook itself is a registration nothing exercises in
  JUnit; the dist smoke's stop path observes the drain log line so the signal path is not
  review-only.

**Slice 2**
- Reconciler unit tests against a host test-double: each diff rule fires the right operation;
  duplicate events and promote's two-file write converge to one action; a torn/malformed state
  read skips the pass without acting.
- IT: `deploy`-shaped file writes against a running gateway — direct upgrade replaces, staged
  canary appears at weight, weight edit reaches the roll, promote swaps, rollback returns v1.
- Host restart mid-canary: kill the gateway, restart, the boot arrangement equals what the
  reconciler had built (boot and live are one function — asserted, not assumed).
- Status write-back: refused candidate's status file carries the refusal message; applied
  deploy's carries the version.
- Membership edit: a new name in the catalogue logs and does not start; removal logs and does
  not stop.

**Slice 3**
- `DeployCommand` against a temp install root: state files written as the library writes them;
  preflight refusals surface with exit 2; 4092 on a catalogue-less directory; `status` renders
  both files; `--wait` returns when the status file lands (and times out loudly).
- `SingleApplicationRefusalTest`'s shape does not apply (`deploy` is a stack command); the
  `OptionSetShapeTest` walk picks the command up automatically — `--stack` is not an option-set
  member, no exemption needed.

## What moves in the docs, and when

With the code PRs, not before: `hosting.md` gains the deploy section (the verb, the canary ramp,
the drain contract and its declared timeout, the overlap window's contract — weight gates HTTP
only, background work participates from candidate start — the ephemeral-port wrinkle, the
two-file protocol and where status lands) and updates the "drives `AppInstaller` from its own tooling" sentence;
`deployment.md`'s expand/contract paragraph is restated as the deploy window's contract, and it
gains the stop-path sentences (SIGTERM drains; the platform's grace period must exceed the
stack's derived drain bound);
`cli-surface.md` gains the mapping row (and its verb-count sentences move to 26);
`reference-cli.md` regenerates; the error reference gains TQL-UPGRADE-4092; `CHANGELOG.md`
(Added: `deploy`, live replace under `host`; the state-file format change if any is a pre-1.0
changelog line, no migration steps); and Decision 29 in `stack-architecture.md` gains the
shipped-status note. `upgrading.md` stays what it is (framework upgrades) — a cross-reference to
`hosting.md`'s deploy section is enough.

## Deliberately not in this design

- **An ops-console deploy page, and the authenticated deploy surface generally.** Slice 7
  territory: its authorization is the stack architecture's open question 4 (`ops.app.<name>`
  semantics). When it comes,
  it writes the same files this design defines — the reconciler is the mechanism either way,
  which is why the files are decided first. The per-application authorization requirement it
  will carry is recorded in its own section above, with the interim that holds the line.
- **Live membership changes.** Adding or removing an application recomposes the stack (4211
  agreement, portal membership, root validation) — a stack deploy by Decision 29's own
  boundary. The reconciler logs the owed restart and touches nothing.
- **Retired-version pruning.** Side-by-side installs accumulate; the previous version's files
  are rollback's working material and are kept. A retention policy (`deploy prune`?) is its own
  small decision, later, with the disk-space measurement it deserves.
- **Metrics-gated automatic promotion or rollback.** The canary's error rates are visible in
  the existing observability; acting on them automatically is policy machinery with real
  failure modes (a flapping promote), and the operator holding the promote decision is the
  design until someone asks otherwise.
- **Multi-node coordination.** A stack is one process (Decision 27); a fleet of nodes sharing
  an install root via image bake deploys by rolling node replacement, which is the
  orchestrator's job, stated in `deployment.md` already. That exclusion leans on a node's stop
  being graceful — which is why the stack's own stop is *inside* this design (the section
  above), not out here.
- **Replacing the surface runtime or the gateway.** Decision 29's boundary, restated: those are
  the stack, and replacing them is deploying it.

## Open questions

Each gated on the slice it blocks, with a recommendation:

1. **The trigger mechanism** — *gates slice 2.* On-disk state + reconciler (recommended, and
   structural decision 2 is written as that answer), versus a notify endpoint or ops-console
   action now. Recommended: the files. Boot already reconciles them, the trust boundary already
   exists, and both rejected candidates land on an authorization surface that is deliberately
   still open — they can be built *on top of* the file protocol later without a second
   mechanism.
2. **The drain policy** — *gates slice 1.* Recommended: the application's own declared
   `tesseraql.shutdown.timeout` / `forceOnTimeout`, unchanged, and no stack-level knob. The
   bound on in-flight work is part of the application's declaration (Decision 26's
   classification), it is already deliberate and visible (audit-hardening Decision 6), and a
   deploy that needs a different bound than a shutdown is a distinction without an operator who
   asked for it. Long-lived streams cut at the force timeout, clients reconnect — stated in
   `hosting.md`. For jobs, recommended: request the cooperative stop **at drain start** (the
   correction above). The alternative — wait most of the bound hoping the run completes, then
   ask — leaves a chunk step no time to reach its next checkpoint, converting deliberate
   checkpoint stops back into force-cuts, which is the opposite of the intent; and the cost of
   asking early is small and bounded (a run between steps stops cleanly with a resume point, a
   run in its final step completes anyway).
3. **The CLI verb's shape** — *gates slice 3.* Recommended: one `deploy` command with a
   positional package and lifecycle subcommands (`promote`, `rollback`, `weight`, `status`), as
   sketched above. Alternatives named for review: a separate `rollout` verb for the lifecycle
   (two verbs, k8s-shaped), or mode flags on one command (`--promote <name>` — the shape the
   option-set work just spent effort removing). The subcommand shape keeps one verb in the
   table and reads as the sentence the operator means.
4. **The ready probe before traffic** — *gates slice 1.* `TesseraqlRuntime.start` returning is
   already a strong gate (routes up, pools built, framework schema validated). Recommended:
   probe `/_tesseraql/health/ready` once anyway before the swap — it is the one check that
   exercises the datasource roll-up end-to-end, it is one HTTP call against a port the host
   already knows, and "the swap only ever installs a runtime that answered ready" is a sentence
   worth being able to say in `hosting.md`. A bounded handful of retries over a few seconds,
   then the replace fails as a no-op.
5. **Per-application deploy authorization: in this design, or gated on the grants work?** —
   *gates nothing in slices 1–3; shapes what comes after.* *(Closed as recommended: the
   grants work is docs/stack-shells.md, and the authenticated deploy surface is its slice 3
   on `tql.app.deploy.<name>`.)* The requirement (a team deploys
   only what it manages) needs an authenticated surface, and its section above records why the
   file protocol cannot carry it. Recommended: **defer the authenticated surface to the
   `ops.app.<name>` grants work (open question 4 of the stack architecture / slice 7)** and
   hold the line with the pipeline boundary in the interim — Decision 23's name contract plus
   per-repository CD permissions, with `hosting.md` stating plainly that install-root access
   is stack-root. Building it now would force that grants question's answer from a side door,
   on an ops shell that is itself still moving — the two-answers-that-add-mechanism signal
   this campaign stops on. The alternative, a minimal deploy-only grant ahead of the general
   model, is named for review and not recommended.
   **Closed in review (2026-08-18), on the recommendation: the requirement stands and is
   wanted eventually; it is out of this design's scope, deferred to the grants work.** The
   section above is the standing record the grants work inherits — deploy is one of its
   customers, the target shape is sketched there, and the interim holds until it ships.
