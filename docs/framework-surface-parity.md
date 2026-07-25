# Framework surface parity

> **Status: designed, not yet implemented.** The compiled app request path has a contract —
> authenticate, authorize, CSRF, localize, one error envelope, security response headers — and the
> framework's own ~25 hand-written `RouteBuilder`s each re-implement a subset of it. Its long-lived
> services have a second contract — close what you open, bound what you accumulate, survive an
> exception in a loop — and re-implement a subset of that. The 2026-07-25 contract-deviation sweep
> confirmed nine deviations across both, two of them reproduced with standalone harnesses: a
> connection leak that pins the main pool at zero, and a live-stream accounting bug that
> permanently degrades every SSE consumer on the node. This document defines the two contracts as
> code and records the deviations.

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
| `StudioRouteBuilder` **reload** | yes | **—** | n/a | — | yes |
| `RouteReloader` compile-failure stub | **—** | **—** | **—** | — | leaks diagnostics |
| `AssetsRouteBuilder` | public *by design* | n/a | n/a | **nosniff only; 404 none** | n/a |
| `SseRoutes` | at connect only | yes | n/a | **—** | **hand-built** |
| `McpRouteBuilder` transport | open *by design* | n/a | n/a | — | — |
| `LoginRouteBuilder` logout | cookie | n/a | **— (GET)** | — | yes |
| `OidcRouteBuilder` / `SamlAcsRouteBuilder` | protocol | n/a | **— (GET logout)** | — | **`{"error": "<string>"}`** |
| `RecoveryRouteBuilder` | pre-auth *by design* | n/a | n/a *by design* | — | yes |
| error responses (all surfaces) | n/a | n/a | n/a | **—** | yes |

## Matrix 2 — the service lifecycle contract

| Service | closes what it opens | bounded accumulation | survives a loop exception | stopped on app close / reload |
| --- | --- | --- | --- | --- |
| `JdbcSessionStore` | yes | yes (TTL + prune) | n/a | yes |
| `LiveStreams` | yes | **— (accounting corrupts)** | yes | yes |
| `LiveEvents` | yes | yes (15-min lifetime) | yes | yes |
| `PgNotifyListener` | **— (RuntimeException path)** | n/a | yes | yes |
| `TopicNotifyBridge` | yes | n/a | **— (RuntimeException kills it)** | yes |
| `RouteWatcher` | yes | n/a | **— (thread dies, unrecoverable)** | yes |
| `InMemorySessionStore` | n/a | **— (no TTL, no cap)** | n/a | n/a |
| `McpHttpHandler` sessions | n/a | **— (no TTL, no cap)** | n/a | n/a |
| `QueueConsumer` `ProducerTemplate` | **—** | yes | yes | **—** |
| `MultiAppGateway` | **— (client, executor)** | **— (unbounded body buffering)** | yes | partial |
| `TesseraqlRuntime.start()` failure path | **— (context never stopped)** | n/a | n/a | n/a |
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

### The default session store never expires anything

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

### `POST /_tesseraql/studio/reload` skips the edit-role gate

Every sibling Studio mutation — draft, apply, scaffold apply — runs
`studioAccess.requireEdit(roles(exchange))` after authenticating, as do all three MCP twins.
Reload authenticates and stops. `RouteReloader.reload(boolean)` has no check of its own.

It bites hardest in the **default** posture, because `readOnly` defaults to true: every other
mutating Studio endpoint 403s while reload succeeds for any bearer principal. `reload(true)` stops
and re-adds every web route and SHA-256-digests the whole app tree — a repeatable authenticated DoS
with an in-flight-request race the code itself calls "the risky, expensive part of a reload".

### The compile-failure stub is unauthenticated and echoes diagnostics

`stopAndRemove` runs before `addRoutes`, so on a compile failure the stub **is** the endpoint — the
secured route is gone, and because route security is compiled into the route body, no outer filter
covers the replacement. The escaping applied to the message is JSON-safety, not redaction, and
compile failures routinely carry route ids, recipe names, and file paths. The behavior is
test-locked today.

It stays low because the stub is a dead end — fixed JSON body, no data access, no mutation, no
session — and reaching it requires a route to be broken, which already implies filesystem or
writable-Studio access. The real cost is diagnostics leaking to anonymous callers during a broken
window, on a URL that has silently lost its 401.

### Error responses carry none of the security headers

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
- `POST /_tesseraql/login` and `POST /_tesseraql/reset` have no rate limit, attempt counter, or
  lockout — the compiled-route `policy.rateLimit` feature these Java routes do not use. Reset also
  queues an outbox mail per call.
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

**Lifecycle is declared, not remembered.** Long-lived services implement a small
`ManagedService` contract (`start`/`close`, plus a `bounded()` self-description for anything holding
per-subject or per-session state), the runtime owns a registry of them, and `close()` walks it — so a
new store cannot be added without a close path. Background loops get one shared `guarded()` wrapper
catching `Throwable`, the pattern `JdbcFileTransferService` already uses correctly and everything
else re-derives.

## Slices

1. **The two leaks and the accounting bug.** `PgNotifyListener`'s `RuntimeException` close,
   `TopicNotifyBridge`'s missing catch, `RouteWatcher`'s `Throwable` guard, and the `LiveStreams`
   eviction fix — each with the regression test the sweep's harnesses already sketch. Independent,
   small, and highest-value.
2. **The Studio reload gate**, plus the stub's security chain and message redaction.
3. **Security headers on every response**, which subsumes the error-response, SSE, and assets rows.
4. **The session-store default.** Either TTL and a cap in the in-memory store, or make `jdbc` the
   default; and honor `tesseraql.sessions.ttl` on both paths so the key stops lying. See the open
   question.
5. **The surface registry and its test** (the guard), which also settles the unverified HTTP leads
   by forcing each surface to declare its posture.
6. **The lifecycle registry and `guarded()`**, closing the template, client, executor, and
   start-failure rows.
7. **The long tail:** login/reset rate limiting, the OIDC/SAML error envelope, SSE session
   re-validation, the scheduling claim-compensation, and the `FrameworkMigrations` component map.

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
