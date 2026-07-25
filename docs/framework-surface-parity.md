# Framework surface parity

> **Status: slices 1, 2 and 4 shipped; the rest designed.** The compiled app request path has a contract —
> authenticate, authorize, CSRF, localize, one error envelope, security response headers — and the
> framework's own ~25 hand-written `RouteBuilder`s each re-implement a subset of it. Its long-lived
> services have a second contract — close what you open, bound what you accumulate, survive an
> exception in a loop — and re-implement a subset of that. The 2026-07-25 contract-deviation sweep
> confirmed nine deviations across both, two of them reproduced with standalone harnesses: a
> connection leak that pins the main pool at zero, and a live-stream accounting bug that
> permanently degrades every SSE consumer on the node. This document defines the two contracts as
> code and records the deviations.
>
> **Slice 1 is shipped:** the `PgNotifyListener` and `TopicNotifyBridge` connection lifecycles
> moved into a `finally` so every exit releases the LISTEN connection, `TopicNotifyBridge` gained
> the `RuntimeException` catch its sibling has, `RouteWatcher` guards each event so one unreadable
> event cannot unwind its thread, and the `LiveStreams` eviction no longer strands the
> subscription it is making room for. Two regression tests pin the leaks; see "What slice 1 could
> not pin" for the one guard that has no direct test and the separate defect that hunt turned up.

The failure class is the same one [route-governance-parity.md](route-governance-parity.md)
addresses for compiled routes, one level out: the framework's own surfaces are written by hand, so
each is an independent chance to omit a step. Where that document's fix is a shared applier for a
compiler, this one's is a shared base plus an assertion, because these routes are Java and will
stay Java.

## Matrix 1 — the HTTP contract

The reference is the compiled route path (`applySecurity` + `TesseraqlAuthProducer` +
`ErrorResponseRenderer` + `ResponseHeaderDefaults`). `—` marks a confirmed gap; `by design` marks
a documented, verified-intentional exemption.

| Surface | auth | role / policy | CSRF on state change | security headers | canonical error envelope |
| --- | --- | --- | --- | --- | --- |
| compiled app routes | yes | yes | yes | yes (success only, below) | yes |
| `OperationsRouteBuilder` | yes (health public *by design*) | yes, deny-by-default | n/a (bearer) | — | yes |
| `IamAdminRouteBuilder` | yes | yes | yes | — | yes |
| `ScimRouteBuilder` | yes | yes | n/a (bearer) | — | yes (SCIM shape by design) |
| `StudioRouteBuilder` mutations | yes | yes | n/a (bearer) | — | yes |
| `StudioRouteBuilder` **reload** | yes | yes (was **—**) | n/a | — | yes |
| `RouteReloader` compile-failure stub | **—** | **—** | **—** | — | redacted (was **leaked**) |
| `AssetsRouteBuilder` | public *by design* | n/a | n/a | **nosniff only; 404 none** | n/a |
| `SseRoutes` | at connect only | yes | n/a | **—** | **hand-built** |
| `McpRouteBuilder` transport | open *by design* | n/a | n/a | — | — |
| `LoginRouteBuilder` logout | cookie | n/a | **— (GET)** | — | yes |
| `OidcRouteBuilder` / `SamlAcsRouteBuilder` | protocol | n/a | **— (GET logout)** | — | **`{"error": "<string>"}`** |
| `RecoveryRouteBuilder` | pre-auth *by design* | n/a | n/a *by design* | — | yes |
| error responses (all surfaces) | n/a | n/a | n/a | yes (was **—**) | yes |

## Matrix 2 — the service lifecycle contract

| Service | closes what it opens | bounded accumulation | survives a loop exception | stopped on app close / reload |
| --- | --- | --- | --- | --- |
| `JdbcSessionStore` | yes | yes (TTL + prune) | n/a | yes |
| `LiveStreams` | yes | **— (accounting corrupts)** | yes | yes |
| `LiveEvents` | yes | yes (15-min lifetime) | yes | yes |
| `PgNotifyListener` | **— (RuntimeException path)** | n/a | yes | yes |
| `TopicNotifyBridge` | yes | n/a | **— (RuntimeException kills it)** | yes |
| `RouteWatcher` | yes | n/a | **— (thread dies, unrecoverable)** | yes |
| `InMemorySessionStore` | n/a | yes (was **no TTL, no cap**) | n/a | n/a |
| `McpHttpHandler` sessions | n/a | yes (idle TTL + cap) | n/a | n/a |
| `McpHttpHandler` sessions | n/a | **— (no TTL, no cap)** | n/a | n/a |
| `QueueConsumer` `ProducerTemplate` | yes (context-owned) | yes | yes | yes |
| `MultiAppGateway` | yes | yes (10 MB request cap) | yes | partial |
| `TesseraqlRuntime.start()` failure path | **— (context never stopped, and hard to reach)** | n/a | n/a | n/a |
| `JdbcFileTransferService` | yes | yes | yes (`guarded()`) | yes |
| `DatasetSpool`, caches, `JwksKeySource` | yes | yes (LRU/TTL) | yes | yes |

## The deviations

### The LISTEN connection leaks until the main pool is dead

`PgNotifyListener.listenAndDrain` assigns the connection field immediately after a successful
open. The `SQLException` branch closes it; the `RuntimeException` branch does not — no `finally`,
no shared helper. The next iteration overwrites the field and the previous connection is
unreferenced and never returned to the pool.

The path is reachable and ordinary: `drainAll()` runs inside `listenAndDrain` while the LISTEN
connection is held, and `JdbcEventChannelStore.claim` wraps `SQLException` into `TqlException`,
which extends `RuntimeException`. The pool is the app's **main** Hikari pool, shared with
migrations, sessions, the job repository, idempotency, and the outbox.

The steady state is worse than a slow leak and better than unbounded growth: once the pool is
exhausted, `getConnection()` itself throws a `SQLTransientConnectionException` — a `SQLException` —
which hits the recovering branch and returns exactly one connection. So the pool pins at zero-to-one
available and the **whole application's** database access is dead, not just messaging. A momentary
pool shortage produces a `TqlException`, which produces a permanent leak, which guarantees the
shortage: positive feedback from a transient condition. There is no `PgNotifyListenerTest`.

### `LiveStreams` eviction corrupts its own accounting, permanently

Two sweeps read this file and disagreed; a standalone harness settled it, and the sweep that called
the eviction "bounded and correct" was wrong. It had verified the synchronization — which is
genuinely sound, all three entry points hold the monitor with consistent lock ordering — and
mistook that for correctness of the eviction algebra. The defect is a stale-reference bug in
single-threaded code that no locking addresses.

The interleaving: a subject holding exactly one subscription and sitting oldest in the
insertion-ordered `bySubject` opens a second stream. `computeIfAbsent` returns its **existing**
list, captured in a local. The global cap fires `evictOldest`, which scans in insertion order and
takes the first non-empty list — that same one. `unregister` empties it and executes
`bySubject.remove(subject)`, detaching the list the caller still holds. The new subscription is
added to the orphan while `byKey` and `total` are incremented. On `close()`, `unregister` looks up
a different or absent list and early-returns.

Reproduced:

```
total = 256  (should be 255)     byKey size = 256  (should be 255)
closed aliceNew still receives signals: true
a live stream was evicted despite spare capacity: true
```

Each occurrence permanently adds one to `total` and strands an entry in `byKey`. `total` ratchets
to the 256 cap and stays there, so every subsequent subscribe evicts a live stream even with real
capacity — the live badge and every `refreshOn:` topic degrade into a reconnect storm that no
amount of idling recovers. Stranded subscriptions still receive signals and are never collected.
The fresh-subject case is safe (`evictOldest` skips empty lists), which is what makes the bug rare
enough to have survived: it needs an *existing* subject with exactly one stream, i.e. an ordinary
long-lived user on a busy node.

Either fix suffices: skip the registering subject in `evictOldest`, or re-read `computeIfAbsent`
after the eviction loops rather than reusing the captured reference. `LiveStreamsTest` exercises
`MAX_PER_SUBJECT` only; nothing touches `MAX_TOTAL`.

### The default session store never expires anything — FIXED

`InMemorySessionStore.session()` is a bare map lookup with no expiry comparison, and there is no
cap, no eviction, and no prune. `JdbcSessionStore` filters on `expires_at` and prunes on every
create.

This is the **production default**: `tesseraql.sessions.store` defaults to `"memory"` and anything
but `"jdbc"` lands here. Worse, `tesseraql.sessions.ttl` is read *only inside the jdbc branch*, so
on the default path that key is silently inert — the config-consumers failure class again, in the
session layer. No layer above enforces expiry (`BrowserAuthenticator` null-checks only; the
`created` map exists for the account page), and the cookie carries no `Max-Age`/`Expires`.

So on a default configuration a session id is valid until the process restarts or the user
explicitly logs out, and the map grows one `Principal` per login forever.
[deployment.md](deployment.md) steering deployments to `jdbc` is the mitigation and is why this is
medium rather than high.

### `POST /_tesseraql/studio/reload` skips the edit-role gate — FIXED

Every sibling Studio mutation — draft, apply, scaffold apply — runs
`studioAccess.requireEdit(roles(exchange))` after authenticating, as do all three MCP twins.
Reload authenticates and stops. `RouteReloader.reload(boolean)` has no check of its own.

It bites hardest in the **default** posture, because `readOnly` defaults to true: every other
mutating Studio endpoint 403s while reload succeeds for any bearer principal. `reload(true)` stops
and re-adds every web route and SHA-256-digests the whole app tree — a repeatable authenticated DoS
with an in-flight-request race the code itself calls "the risky, expensive part of a reload".

### The compile-failure stub is unauthenticated and echoes diagnostics — half FIXED

`stopAndRemove` runs before `addRoutes`, so on a compile failure the stub **is** the endpoint — the
secured route is gone, and because route security is compiled into the route body, no outer filter
covers the replacement. The escaping applied to the message is JSON-safety, not redaction, and
compile failures routinely carry route ids, recipe names, and file paths. The behavior is
test-locked today.

It stays low because the stub is a dead end — fixed JSON body, no data access, no mutation, no
session — and reaching it requires a route to be broken, which already implies filesystem or
writable-Studio access. The real cost is diagnostics leaking to anonymous callers during a broken
window, on a URL that has silently lost its 401.

### Error responses carry none of the security headers — FIXED

`ResponseHeaderDefaults.mergeUnder` is reachable only through the compiler's `withDefaultHeaders`
and materializes only in `HtmlResponseRenderer.applyHeaders` — the last step of a *successful*
render. The compiler's only error wiring is `onException(...).handled(true).process(new
ErrorResponseRenderer(...))`, which short-circuits that render. Both HTML error surfaces — the
custom error page and the htmx fragment swapped into an already-loaded page — ship with no CSP, no
`X-Frame-Options`, no nosniff, no `Referrer-Policy`.

Low rather than higher, and the reason matters for prioritization: bodies are escaped and messages
are generic status phrases, so there is no injected content for a CSP to contain. It is a gap
against [response-shaping.md](response-shaping.md)'s "the security header block every page sends",
whose scope note never mentions error responses.

### Logout is a state-changing GET

Confirmed as fact across all three providers, and **refuted as a vulnerability** — worth recording
so the fix is not mis-sized. All three session cookies are `SameSite=Lax`, which withholds the
cookie on cross-site subresource requests, so `<img src>`, `fetch`, and hidden iframes cannot log
anyone out. Only a top-level navigation carries it, which requires a visible click landing the
victim on the login page. Impact is forced logout: no data loss, no authorization change, no
disclosure. The one amplification — chaining into IdP-wide single logout — is still availability.

The real defect is REST semantics. A state-changing GET is reachable by link prefetchers, security
scanners, and mail-client link previews, which can log users out by accident. Fix it as POST + CSRF
for hygiene and consistency with the `logout-others` sibling two lines away; do not track it as a
vulnerability.

### `MultiAppGateway` does not behave like the trusted edge it forwards to

`SKIP_HEADERS` strips hop-by-hop plus `host`/`content-length` and forwards everything else verbatim,
including a configured mTLS certificate header and `X-Tenant-Id`, which the gateway also reads
straight from the client for its entitlement check.

Three facts keep this low, and all three should be stated wherever it is tracked: `start()` has
**zero non-test callers**, so nothing ships in this configuration; mTLS is opt-in with no default
`forwardedHeader` and `MtlsAuthenticator` re-parses, validity-checks, and PKIX-validates against a
configured bundle; and [deployment.md](deployment.md) already makes stripping client-supplied copies
the operator's job. The residual legitimate finding is the design smell in the header: TesseraQL's
own front should strip the configured mTLS header and `X-Tenant-Id` on ingress rather than pass them
through — a client certificate is public, so PKIX proves issuance, not possession, and header trust
remains the actual control.

### The rest

- **`RouteWatcher`'s thread dies permanently on `UncheckedIOException`.** Reproduced: walking a tree
  whose subdirectories vanish mid-iteration raises it, and it is a `RuntimeException` that escapes
  the `catch (IOException)`. `run()` catches only `ClosedWatchServiceException | InterruptedException`,
  the thread has no `UncaughtExceptionHandler`, and `start()` returns early while the field is
  non-null — so the watcher is dead for the life of the process. The class javadoc's promise ("a
  reload failure never kills the watcher") is *honored*; the escape comes from directory
  registration, not reload. `serve --watch` only, never production serving.
- **`TopicNotifyBridge` catches only `SQLException`** while its javadoc claims it mirrors
  `PgNotifyListener`, which also catches `RuntimeException`. Same shape as the leak above, narrower
  trigger; the fix is the same one line.
- **`TesseraqlRuntime.start()`'s failure path** closes pools and lanes but never calls
  `context.stop()`, leaking the bound HTTP port and the notify threads added via `addService` before
  the throw. Matters wherever start is retried in-process.
- **`McpHttpHandler` sessions grow monotonically** — the 404 says "Unknown or **expired** session"
  and nothing expires. The transport does no auth by design, so entries are attacker-drivable.
- **`ProducerTemplate`s from `createProducerTemplate()` are never stopped**, and `QueueConsumer`'s
  lazy init is a non-atomic check-then-create on a volatile field, so two callers can each build one
  and orphan the loser.
- **Studio's try-it console builds an `HttpClient` per invocation** and abandons it, where every
  other call site in the codebase caches one.
- **`MultiAppGateway.close()`** releases neither its `HttpClient` nor its virtual-thread executor,
  and the gateway buffers whole request and response bodies with no cap on a virtual-thread-per-request
  server.
- **`FrameworkMigrations.COMPONENTS` registers 2 of ~14 framework schema families**; the rest rely on
  each store's idempotent `ensureSchema()` with no versioned history, so a future non-idempotent
  column change has no upgrade vehicle. Possibly deliberate; the enumerated map has certainly not
  kept pace.
- **`SchedulingRouteBuilder` claims a firing before executing it**, so an exception burns that
  firing cluster-wide with no compensation — the one claim-based sweeper without a retry path
  (`WorkflowSweepRoutes` shares the shape). Camel's error handler keeps the timer firing, so this is
  a lost firing, not a dead route.

## What slice 1 could not pin

The `RouteWatcher` guard ships **without a direct regression test**, and the attempt to write one
is worth recording because it found something else.

The confirmed trigger — a directory deleted while `Files.walk` is iterating it — is a filesystem
race, so the obvious deterministic substitute was an unreadable directory moved into the watched
tree fully formed. That turns out to exercise a *different* path: `registerTree` reports the
failure as a checked `AccessDeniedException`, which `accept()`'s existing `catch (IOException)`
already handles. The staged failure never reaches the guard.

It did, however, surface a real and separate defect: **one unreadable directory anywhere under
`web/` stops every subsequent hot reload.** The watcher survives and keeps reporting, but each
debounced reload walks `web/` to load the manifest, hits the unreadable directory, and fails:

```
Watch: reload failed after web/api/alive/get.yml (+1 more) changed:
  java.nio.file.AccessDeniedException: .../web/api/blocked/inner
```

A new route created after that point never mounts, and the only signal is the watch line — which
`serve --watch` prints but a test asserting over HTTP never sees. Dev-loop only, and it does fail
loudly rather than silently, so it is a lead rather than a defect to fix blind: the question is
whether an unreadable subtree should be skipped with a warning (the manifest load is already
tolerant of broken *route documents*) or remain fatal. Deciding that belongs with the reload
tolerance model, not with a thread-safety fix.

## Unverified leads

Raised by the sweep, not examined by a verifier. Listed so the slices below can decide them, not
because they are established.

- Static assets set `nosniff` only, and the 404 branch — the one a hostile path reaches — sets
  nothing. `nosniff` does not neutralize script inside an SVG rendered as a top-level document.
- SSE streams carry no security headers and build their error envelope by string concatenation,
  emitting the *internal* exception message where every other endpoint returns a generic localized
  status phrase, with escaping that handles quotes and backslashes but not control characters.
- SSE streams authenticate at connect and never re-validate, so "sign out others" and a password
  reset do not end an already-open stream for up to its 15-minute lifetime — against
  [security-hardening.md](security-hardening.md)'s claim that a credential change evicts a parallel
  session.
- OIDC and SAML return `{"error": "<string>"}` rather than the framework envelope, carrying no
  `TQL-SEC-*` code, and collapse genuine 500s into 400 via `onException(Exception.class)`.
  **Shipped:** both answer the framework envelope through a shared `FederationErrors`, an
  authentication failure keeps `TQL-SEC-4011`, and the catch-all now distinguishes a `TqlException`
  (its own code and status) from an unexpected failure (`TQL-SEC-4140`, 500) — because at a
  federation boundary, whose fault it is happens to be the most useful thing the answer carries,
  and 400-for-everything asserted it was always the caller's.
- `POST /_tesseraql/login` and `POST /_tesseraql/reset` have no rate limit, attempt counter, or
  lockout — the compiled-route `policy.rateLimit` feature these Java routes do not use. Reset also
  queues an outbox mail per call.
  **The obvious fix is a wrong one, so it is written down here before someone ships it.** Both
  shipped limiters — `RateLimiter` and `ClusterRateLimiter` — hold a *single* bucket per route;
  `ClusterRateLimiter`'s `scopeKey` names the route for cluster coordination, not the caller.
  Attaching either to the login route makes every attempt in the system draw on one budget, so one
  attacker locks every user out with a script. That trades an online-guessing weakness for a
  trivial denial of service, which is not an improvement — it is a different, cheaper attack.
  What login needs is a limiter keyed per caller (remote address, and separately per `loginId` so a
  distributed attempt on one account is still bounded), with eviction, which neither existing class
  provides. Reset needs the same keying plus a per-address cap on queued mail, since each call
  costs an outbox row and a message. That is a slice, not a line — and its absence is safer than a
  global bucket would be.
- Session cookies are issued without `Secure` at every site and there is no knob to add it. Uniform
  and explicitly reasoned in [threat-model.md](threat-model.md) ("HTTPS-secured at the edge"), which
  does not cover the first request before HSTS is known.

## The model

**One base for framework HTTP routes.** A `FrameworkRoutes` helper supplies the contract steps —
authenticate, apply the security header block, render errors through `ErrorResponseRenderer` — so a
new builder gets them by construction. Exemptions (`health` public, MCP discovery open, recovery
pre-auth) are declared at the call site with a reason, not expressed as an absence.

**Security headers move to the response, not the render.** The header block applies to every HTTP
response leaving the runtime, including error responses, SSE, and assets. The compiler's
`withDefaultHeaders` becomes the *source* of the values; a single late step applies them. This is
the one change that closes four rows of Matrix 1 at once.

**A surface registry, tested like the config-key registry.** `FrameworkSurfaces` enumerates every
framework-mounted route with its declared contract (auth mode, role requirement, CSRF, public-by-design
flag). A test walks the started `CamelContext`, matches every mounted framework route against the
registry, and fails on an unregistered route or a declared-but-absent step. That is what would have
caught both the reload gate and the reload stub, which are precisely the routes nobody thought to
list.

*Measured before building it, then measured again properly.* Counting `routeId("...")` literals in
the source gives **71**, and that number is wrong — it was the first thing the guard's own fixture
disproved. A started context with every surface enabled mounts **173** framework HTTP routes,
because most of `tql.*` (123 of them: the account pages, the inbox, the ops console, the Studio UI)
is generated rather than written as a literal. Counting the source was measuring the thing that was
easy to count.

Two consequences. First, a full per-route posture table is a 173-entry artifact, not a 50-entry
one. Second — and this is what made the slice tractable — only **18** of those 173 answer without
an `authenticate` step, so the reviewable set is the exemptions, not the postures.

*And there are three states, not two.* A step-based check alone calls `system.logout.others`
unprotected. It is not: it resolves the session and validates the CSRF token inside its processor.
Recording that as "public by design" would have put a falsehood in the registry built to prevent
falsehoods. So `FrameworkSurfaces` distinguishes `PUBLIC_BY_DESIGN` from `PROCESSOR_ENFORCED`, and
the latter names the method that enforces it, so the claim can be checked rather than believed.

Unlike the model-field registry in [yaml-surface-consumers.md](yaml-surface-consumers.md), a
hand-written entry is right here: "what posture should this surface have" is not derivable from
the code — it is the decision the code is supposed to implement.

**Lifecycle is declared, not remembered.** Long-lived services implement a small
`ManagedService` contract (`start`/`close`, plus a `bounded()` self-description for anything holding
per-subject or per-session state), the runtime owns a registry of them, and `close()` walks it — so a
new store cannot be added without a close path. Background loops get one shared `guarded()` wrapper
catching `Throwable`, the pattern `JdbcFileTransferService` already uses correctly and everything
else re-derives.

## Slices

1. ~~**The two leaks and the accounting bug.**~~ **Shipped.** Both listeners release their
   connection from a `finally` (so the unchecked path the event store actually raises cannot skip
   it), `TopicNotifyBridge` gained the `RuntimeException` catch its javadoc already claimed,
   `RouteWatcher` guards per event, and `LiveStreams` evicts before taking the list reference —
   plus a progress guarantee on the global-cap loop, since a corrupted `total` could otherwise
   spin it forever holding the monitor. `PgNotifyListenerTest` and a new `LiveStreamsTest` case
   pin the two; both were confirmed to fail without the fix.
2. ~~**The Studio reload gate**~~ **and the stub's message redaction: shipped.** Reload takes the
   same `requireEdit` its siblings do — it matters most in the default posture, where `readOnly`
   is true and every other mutating Studio endpoint already 403s. The stub now returns its code
   with no cause; the compile message, which names absolute paths, SQL text, and column names,
   goes to the log. Two integration tests pinned the leak and were rewritten to assert the
   redaction — a deliberate contract change.
   **Still open:** giving the stub a security chain. It is mounted by a bare `RouteBuilder` in
   the runtime while `applySecurity` lives in the compiler, so wiring it needs the compiler's
   chain to be reachable from a reload — worth doing with the surface registry (slice 5) rather
   than by duplicating the chain.
3. **Security headers on every response** — **the error-response row is shipped**; SSE and assets
   are not, and the reason is worth recording rather than leaving as an omission.
   `ErrorResponseRenderer` now carries the app's `security.responseHeaders` and applies them to
   both HTML surfaces it produces (the custom error page and the htmx error fragment). An
   integration test pins it, confirmed to fail without the change.
   **SSE and assets are shipped too, and the mechanism is the opposite of the one considered.**
   The design leaned toward a response-wide hook at the platform-http layer, and there is none:
   `VertxPlatformHttpServerConfiguration` exposes nothing beyond a body handler, a Camel
   `RoutePolicyFactory` covers mounted routes only, and its `onExchangeDone` fires *after* a
   stream has begun — too late for SSE, which needs its headers before the first frame. Chasing a
   single late hook was the wrong shape.
   What these two surfaces have in common is not their timing but their authorship: they write
   their own responses, outside anything the compiler produces. So each applies the block where it
   writes its headers, reading it from the registry (`RESPONSE_HEADERS_BEAN`) rather than through
   two constructors. The remaining gap is genuinely narrow and named: a 404 from a path nothing
   mounts still answers without the block, because no code of ours runs.
   An integration test pins the asset case and was confirmed to fail without the change.
4. ~~**The session-store default.**~~ **Shipped, the first half.** The in-memory store expires on
   read and prunes on write, with a 50,000-session ceiling behind that, and
   `tesseraql.sessions.ttl` is read once and handed to whichever store is selected — the key had
   been read inside the jdbc branch only, so on the default it was inert.
   **Open question 1 is deliberately still open.** Making `jdbc` the default is a deployment
   posture, not a defect: it would also make sessions survive a restart, which is a visible
   behavior change for every existing app and belongs to whoever owns that call. Fixing the
   silent-no-expiry defect did not require deciding it, so this slice did not decide it.
   The no-TTL constructor is kept for embedders, and a test pins that too, so the change is
   about the framework's default rather than the class's only possible posture.
5. **The surface registry and its test** (the guard). **Shipped, as the exemption half.**
   `FrameworkSurfaces` records the 18 framework HTTP routes that answer without an `authenticate`
   step — `PUBLIC_BY_DESIGN` with a reason, `PROCESSOR_ENFORCED` naming what enforces it — and
   `FrameworkSurfaceGuardTest` starts a context with every surface mounted and fails on a route
   that neither authenticates nor appears there. Both directions were probed: removing an
   exemption reports the route, and adding one for a route nobody mounts reports the entry. The
   fixture's own honesty check requires all six families and a floor on the route count, because a
   guard that quietly stopped mounting Studio would pass loudest when it checked least.
   **Still open:** the full per-route posture table (auth mode, policy, CSRF for all 173), and with
   it the unverified HTTP leads — the exemption guard is what makes a *new* open surface fail,
   which was the security half; declaring every existing posture is the documentation half.
6. **The lifecycle registry and `guarded()`**, closing the template, client, executor, and
   start-failure rows.
7. **The long tail:** login/reset rate limiting, the scheduling claim-compensation, and the
   `FrameworkMigrations` component map remain. Rate limiting needs the keyed limiter described
   with the deviation above — the shipped ones hold one bucket per route, and attaching one to
   login trades an online-guessing weakness for a trivial lockout of every user.
   **The OIDC/SAML error envelope is shipped:** both answer the framework envelope through a
   shared `FederationErrors`, and the catch-all distinguishes a `TqlException` from an unexpected
   failure instead of reporting every one as the caller's fault.
   **SSE session re-validation is shipped**, with the envelope leak beside it: every frame
   re-checks the session, and the refusal answer returns its code with a generic phrase rather
   than concatenating the internal exception message into JSON. Closing the stream turned out to
   be the part that was missing — the existing `IOException` branch logged the end and left the
   response open, so a client held a stream that would never produce again. The test caught that
   by hanging, which is the honest way to find it.
   *A note from attempting the start-failure row first, since it looked like the smallest.* The
   one-line shape is obvious — stop the context before closing the pools underneath it — and it
   was written and then dropped, because two inducements failed to reach the window: an
   unparseable cron survives Quartz's trigger build, and an already-bound port does not fail the
   start. Without a failing case there is no evidence the guard condition
   (`isStarted() || isStarting()`) is even true on the real path, and a fix nobody can make fire
   is not a fix, it is a comment. Whoever takes this row should induce the failure from inside —
   a `RouteBuilder` that throws on start is the obvious lever — and only then decide the
   condition. The leak is real; its rarity is why nothing has hit it, and why it is worth doing
   properly rather than plausibly.
## Out of scope

- **Rewriting framework routes as compiled YAML routes.** They exist in Java because they are
  framework surfaces with framework lifetimes; the contract is shared, the implementation stays.
- **`Secure` on session cookies as a code change.** The threat model's edge-TLS stance is a
  position, not an oversight; revisit it there, not here.
- **`MultiAppGateway` hardening beyond ingress header stripping** while it has no production callers.
  If it gains one, its rows in Matrix 1 become a blocking design of their own.

## Open questions

1. Should `jdbc` become the default session store? It fixes TTL, cap, and multi-node correctness in
   one line and costs a table on first boot for the single-node case the memory store serves.
   Leaning yes — the current default is the only one that silently ignores its own TTL key.
2. Does the security-header step belong in the platform-http layer (covering every response
   including 404s from unmounted paths) or in a framework route base (covering only what the runtime
   mounts)? Leaning platform-http: the 404 branch is exactly the one an attacker reaches.
3. Should `LiveStreams`' global cap evict at all, or refuse the new subscription with a clear error?
   Eviction silently kills someone else's live view to serve this one. Leaning refuse-with-error at
   the global cap and keep eviction only for the per-subject cap, where the victim is the same user.
