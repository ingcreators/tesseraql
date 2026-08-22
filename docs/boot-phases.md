# The boot is phases, not a method

Implementation design for splitting `TesseraqlRuntime.start` — at 2,344 lines the largest method
in the tree by a factor of six — into named pieces, without inventing a boot framework to do it.
Written 2026-08-22, measured against main at #985 (post the whole-code review's fifteen fixes,
four of which had to edit this method and each paid its size as a tax: a reassignment that broke
eleven lambda captures, a declaration hoisted above a `try` purely to survive it, a seam record
that had to learn lazy ports because it binds before the server listens).

The method is not badly written — it is a **readable sequence with no framework debt**, and that
is the property to keep. What it lacks is names: forty-five phases share one scope, so every
local is potentially coupled to every later line, and the compiler cannot tell a reviewer which
couplings are real. The measured long-range couplings (below) are the actual cost.

## What is true today, measured

**The shape.** One private static 11-arg overload, lines ~417–2760: a construct-and-bind prefix
(~428–1332, twenty-three phases building pools, stores and beans), a `try` block (~1333–2736,
twenty-two phases compiling routes, registering providers, scheduling sweeps, and starting), a
release-everything `catch` (~2737–2755), and the constructor call. Two funnels reach it: every
unhosted caller through the 8-arg adapter, the stack through the `HostContext` overload.

**The three giant inline blocks.** Beyond the sequence itself, three blocks account for ~825
lines and are the only parts with no existing home:

| Block | Lines | What it is |
| --- | --- | --- |
| `serviceProviders` chain | ~375 | one fluent statement registering ~41 `ops.*` / `account.*` / `auth.*` / `iam.*`-session providers |
| IAM-admin provider block | ~360 | ~50 `iam.*` grant-administration registrations; only `serviceProviders` and two lazy lookups cross its boundary |
| job runner closures | ~88 | `runOne` + the after-chaining BFS, capturing eight locals |

**The long-range couplings.** The locals that make naive extraction fail, with creation → last
consumer: `context` (436→2757, everywhere), `manifest` (428→2759, ~120 config reads),
`dataSources`/`lanes`/`tenantDataSources`/`pinningSource`/`otelSdk` (all created before line
550, consumed **only** by the catch block and the constructor two thousand lines later),
`sseEndpoints` (689→2736, the deferred-install list drained after `context.start()`),
`opsDashboard` and `outboxSink` (declared outside the `try`, assigned inside — the
declaration/assignment splits exist for the catch), `jobs`/`jobOwners`/`hostedApps` (created at
~1212, **mutated** by the system-apps phase at ~1398, read by six later phases).

**The error-path contract.** The catch releases in a load-bearing order: `context::close` (the
services hold the port and the JDBC listeners), then diagnostics (`pinningSource`, `otelSdk`),
then pools (`tenantDataSources`, `lanes`, every Hikari pool), then the module classloader.
Failures before the `try` (the pool-building prefix) propagate raw today and leak what was
built — a pre-existing gap this design fixes in passing, because giving the prefix a name gives
it an owner for its own failure.

**The seams that already exist.** The method already delegates well at its edges: config loaders
(`DataSources.createAll`, `LaneConfigs.load`, `SecurityConfigFactory.build`, …), migrations,
`SystemApps`, the route installers (`McpRoutes`, `OperationsRoutes`, `LoginRoutes`, …), the
self-scheduling sweeps, and three provider registrars (`PortalProviders`, `OpsShellProviders`,
`OAuthAccountProviders`). Two patterns in particular are the template this design reuses rather
than replaces:

- **`StudioProviders` + `RuntimeSeams`**: Studio was pulled out of this method wholesale — the
  boot binds one record of "what an out-of-line phase needs" and the extension registers its
  providers from it. The record's `port` is an `IntSupplier` because the seams bind before the
  server listens; any extraction below inherits that rule.
- **`HttpEdgeBeans`**: the five post-start installers reach the router through the context, never
  through a local — which is what makes them position-independent. An extracted phase that needs
  another phase's product should prefer the bean it already binds over a new parameter.

**The ordering constraints** (verified, must survive any split): every bean a compiled route
resolves binds before `RouteCompiler` runs; `RUNTIME_SEAMS_BEAN` and the two OAuth surface beans
bind before the extension-discovery loop; `SERVICE_PROVIDERS_BEAN` binds after the last
registration; `SHUTDOWN_TIMEOUT_BEAN` binds before `context.start()`; the `sseEndpoints` drain
runs after it.

## Structural decision 1: extract by the existing patterns, and only where a block has no name

No `BootPhase` interface, no lifecycle framework, no DI container. The method's failure mode is
namelessness, not procedure — so the fix is names: each extraction below is a plain class with a
static entry point, shaped exactly like the registrars and installers that already exist. A
framework would re-introduce the property Camel's removal spent three campaigns deleting: a
lifecycle that runs code the reader cannot see from the call site.

The target is **`start` under ~600 lines**: the sequence of named calls, the ordering
constraints visible as their order, and the catch block unchanged in meaning. The number is a
target, not a gate — no test asserts it, because a line-count guard would push complexity into
whichever class is under the counter.

## Structural decision 2: what a phase hands back, the catch must still see

The catch block's locals are the contract. An extraction that builds releasable resources
(pools, diagnostics, the SDK) returns them as a record the method destructures, so the catch
keeps releasing exactly what it releases today, in the same order. The prefix extraction (slice
4) additionally closes its own partial work on its own failure — the pre-`try` leak, fixed by
ownership rather than by a wider try.

## Slices

Each slice is one PR, `main` green between any two; the full suite is the acceptance for every
slice, as it was for the vertx-native campaign. Ordered so the mechanical extractions
(provider blocks — verbatim moves with a proven template) land before anything that touches
construction order.

1. **`IamAdminProviders`.** The ~360-line `iam.*` grant-administration block moves verbatim to
   `IamAdminProviders.register(serviceProviders, deps)` beside `OpsShellProviders`. Its two
   lazy `context.lookup` suppliers move with it unchanged — they are lazy because
   `IDENTITY_SERVICE_BEAN` binds later, and that stays true. **Done, #988** — one capture the
   map missed (`manifest`, a single orgunit read), and its lesson: a config-key read that
   *moves file* drifts the generated config reference, so the regen runs on moves, not only on
   adds and removes.
2. **`OpsAccountProviders`.** The ~375-line fluent chain moves the same way. One class, three
   register methods (`ops`, `account`, `iam` session/credential) in chain order — the
   `StudioProviders` grouping precedent, state as fields so the lambda bodies stay verbatim.
   **Done, #989** — the real capture list was 26 values, eleven beyond what reading the chain
   suggested; the compiler's enumeration of them is itself the argument for the extraction.
   Landed with a latent test race retired in passing: `StackReconcilerSweepTest`'s fake host
   shared a plain map and list across threads, and once failed asserting a list did not
   contain the very element its own error message printed.
3. **`JobRunners` + `OpsDashboards`.** The `runOne`/`jobRunner` closures become a small factory
   returning the two runnables; the dashboard's eight-probe builder chain moves next to it.
   Both are pure assembly over already-built stores. **Done, #990**, taking `traceLogOf` and
   the datasource probe with the dashboard — both had no other caller.
4. **The construct-and-bind prefix becomes three builders.** `RuntimePools` (datasources,
   framework pool, tenant pools, lanes, telemetry, diagnostics — everything the catch releases),
   `RuntimeStores` (session through attachments: the P12–P16 store ladder), and
   `RuntimeMessaging` (outbound HTTP, notifications, inbox, topics). Each returns a record; each
   closes its own partial work if it throws, which retires the pre-`try` leak. This is the
   riskiest slice and deliberately last: re-measure after slices 1–3, and if `start` is already
   a readable ~900 lines of named calls plus this prefix, **stopping there is an allowed
   outcome** — the abort clause, stated up front like vertx-native's.
   **Done in narrowed form — `RuntimePools` alone, plus the whole leak.** Shipping found the
   leak did not need the other two builders: hoisting the boot's `try` to start right after the
   pools phase (the `return` moves inside it) puts every later failure — stores, messaging,
   compile, start — onto the catch that releases the record, and `RuntimePools` releases its
   own partial work for failures inside itself. With the leak retired by ownership,
   `RuntimeStores` and `RuntimeMessaging` would have carried names only, for a bind ladder
   whose comments already name its rungs — the abort clause, applied to two-thirds of the
   slice. A pools-phase refusal keeps its own message (the wrapper applies only after the
   record is handed back), which is the key-naming contract the threading suite pins.
   `BootFailureReleaseTest` pins both failure behaviours by watching for the pool's own
   surviving threads. `start` measured 2,344 lines at the campaign's start and 1,396 after
   this slice; the largest method left in the tree is a 378-line provider group.

   **Revised by the post-campaign review.** The hoist had split refusals into two exception
   types: a pools-phase `TqlException` escaped raw with its code, while the same class of
   config refusal after the phase surfaced as `IllegalStateException` — the code invisible
   unless a caller unwrapped the cause. The boot's catch now releases and rethrows
   `TqlException` raw on every path (one refusal contract, the one the pools phase already
   pinned); the `Failed to start TesseraQL runtime` wrapper marks only a failure the boot did
   not anticipate. The same review closed three leak paths the slice's observable could not
   see: the catch takes `Error` too (ServiceLoader over an application's module jars throws
   `ServiceConfigurationError` for a broken descriptor, and the old clause let it strand every
   pool), each start overload owns the module classloader until the boot's own handling takes
   over (a malformed `server.port` or a pools-phase refusal used to strand the loader and its
   open jar handles), and the failure paths release executors before the pools their work
   borrows connections from — `close()`'s order, which the catch had inverted.

Not in any slice: the MCP assembly, `systemNav`, the token-issuance block, the sweeps — each is
30–70 lines with a comment that names it, and extraction would trade a visible sequence for a
file hop. If slice 4 runs, they stay where they are either way.

## Guards

- The existing suite is the guard, deliberately: every phase's behaviour is pinned by the
  integration tests that already boot runtimes hundreds of times per verify.
- One new test in slice 4: a boot that fails inside the prefix (an unresolvable second
  datasource) leaves no live Hikari pool behind — the leak that today's raw propagation permits.
  Nothing else new: a release-order unit test would pin the catch's text rather than its effect.

## Deliberately not in this design

- **No boot framework** (decision 1). Named static entry points, records for hand-backs.
- **No bean-binding reordering.** The constraints table above is a fact this design preserves,
  not a shape it improves; reordering binds is its own campaign if ever wanted.
- **No change to the overload ladder.** The two funnels and the 11-arg private form stay; the
  ladder is eight small methods whose diffs are readable.
- **No `StudioProviders`-style extension conversion** for the extracted providers. The runtime's
  own providers are not optional features; `RuntimeSeams` stays the extension seam it is.
