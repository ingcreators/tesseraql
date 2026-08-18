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

**The drain is the one the application already declared.** The swap happens first, so new
requests reach the new runtime; the old runtime then gets `close()`, whose Camel shutdown
strategy drains in-flight exchanges under the application's own `tesseraql.shutdown.timeout` and
force-stops at the bound it declared. In-flight job runs stop cooperatively (the batch
platform's stop, as on any node shutdown) and the reaper covers stranded rows, as it does today.
Long-lived streams — SSE, exports — are cut at the force timeout; their clients reconnect and
land on the new version. No second drain knob is introduced: the timeout is part of the
application's declaration (Decision 26's classification), and a stack-level override would be
two numbers for one bound.

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

The verb count moves from 25 to 26 (`cli-surface.md`'s mapping table gains one row);
`deploy` takes `--stack` explicitly like `host` does — production does not guess (Decision 9's
`host` rule applies: the flag is required, no discovery).

## Slices

Three PRs, each independently green and observable:

| # | Slice | Contents | End state |
| --- | --- | --- | --- |
| 1 | The host operation | Live slots holding entry+runtime; `replace`/`stageCanary`/`setCanaryWeight`/`promoteCanary`/`discardCanary` with admission checks, ready probe, swap-then-drain; relay live entry/strip lookups + proxy eviction | The library-level replace exists and is proven under load; nothing calls it in production yet (the #831 `AppDirectory` precedent) |
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
the drain contract and its declared timeout, the ephemeral-port wrinkle, the two-file protocol
and where status lands) and updates the "drives `AppInstaller` from its own tooling" sentence;
`deployment.md`'s expand/contract paragraph is restated as the deploy window's contract;
`cli-surface.md` gains the mapping row (and its verb-count sentences move to 26);
`reference-cli.md` regenerates; the error reference gains TQL-UPGRADE-4092; `CHANGELOG.md`
(Added: `deploy`, live replace under `host`; the state-file format change if any is a pre-1.0
changelog line, no migration steps); and Decision 29 in `stack-architecture.md` gains the
shipped-status note. `upgrading.md` stays what it is (framework upgrades) — a cross-reference to
`hosting.md`'s deploy section is enough.

## Deliberately not in this design

- **An ops-console deploy page.** Slice 7 territory: its authorization is open question 4's
  `ops.app.<name>` semantics. When it comes, it writes the same files this design defines —
  the reconciler is the mechanism either way, which is why the files are decided first.
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
  orchestrator's job, stated in `deployment.md` already.
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
   `hosting.md`.
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
