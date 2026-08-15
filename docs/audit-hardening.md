# Audit hardening

Status: **designed 2026-08-15** — thirteen slices below, none shipped yet. §Order records the
sequence they land in and why.

Two audits of the Camel dependency — what it does today, and whether to lean on it further —
found almost nothing worth changing about Camel and eleven things worth changing about
TesseraQL. That is the honest summary of both exercises: the framework question resolved to
"keep it, narrowly", and the yield was a defect list found by reading Camel's configuration
surfaces as specifications and checking whether this framework meets them.

Eight of the eleven must land before 1.0, because each adds or changes a declared config key,
a default, an error code, or a wire-visible status. The ninth, MCP authorization, is here for
a different reason: the framework currently cannot accept an OAuth bearer token at its MCP
endpoint at all, and the specification that governs that endpoint has moved twice since the
posture was written down.

Nothing here is a Camel adoption. Two decisions consume a Camel SPI (`IdempotentRepository`,
`ShutdownStrategy`) with TesseraQL implementations behind them, and one adopts a Camel jar
that is already on the classpath (`camel-health`) as a signal rather than a gate.

## What the audits found

| # | Defect | Where | Consequence |
| --- | --- | --- | --- |
| 1 | The bearer path never checks `aud`, and an `exp`-less token never expires | `JwtAuthenticator.java:109-124` | A token an external IdP minted for another relying party is accepted |
| 2 | The MCP endpoint has no transport authentication and no way to advertise one | `TesseraqlRuntime.java:1133` | No conformant MCP client can authenticate; the two server-side MUSTs are unmet |
| 3 | SQL Server constraint failures classify as generic integrity failures | `SqlErrors.java:23-55` | Job claiming, SAML replay, webhook replay and the duplicate-create contract all degrade |
| 4 | A poll source has no cross-node exclusion on `sftp`/`ftps` | `PollingRouteBuilder.java:110` | Every replica imports every file |
| 5 | Telemetry is shut down before the drain it observes; a failed boot leaks a bound port | `TesseraqlRuntime.java:2671-2681`, `:1875-1880` | Shutdown spans are dropped; a boot failure leaves the HTTP server running |
| 6 | A batch run has no owner, no heartbeat, and an unbounded stop | `V1__framework_operations.sql:6-17` | A killed replica leaves a RUNNING row that wedges `overlap: skip` forever |
| 7 | The log's trace id and the exported span's trace id are unrelated values | `CompositeTracer.java:66-74` | The OTLP export cannot be reached from a log line |
| 8 | Suite mode has no tracing at all | `TesseraqlRuntime.java:164` | The console's trace pages are permanently empty behind `tesseraql host` |
| 9 | No trace context crosses the process boundary in either direction | no references repo-wide | An app is always a trace root and never propagates |
| 10 | The framework surface registry attests a gate that does not run | `FrameworkSurfaces.java:73-78` | The repo asserts something about itself that is not true |
| 11 | No JVM, GC, thread or pool metrics; the readiness probe rebuilds trace trees per poll | `OpsDashboard.java:150-174` | An operator cannot answer "is it out of heap"; an unauthenticated endpoint does real work |

Three of these were found only because a Camel option's *name* was checked against its
*bytecode*. That method is the campaign's one methodological rule, and §Traps records where
it already caught a fix that would have shipped as a non-fix.

## Decision 1 — a bearer token is bound to this application, and a bearer route says who may call it

`JwtAuthenticator.validateClaims` (`tesseraql-security/.../jwt/JwtAuthenticator.java:109-124`)
checks `exp`, `nbf` and — only when configured — `iss`. The string `aud` does not appear
anywhere in `tesseraql-security`. Because `auth: bearer` supports RS256 against an external
IdP's JWKS (`jwksUri`, `issuer`), and because `RouteCompiler.applySecurity` (`:1764-1778`)
emits the authorize step only when `policy:` is non-blank, a bearer route without a policy
accepts any token that IdP minted for any relying party. That is a confused deputy.

The rationale for the gap is written down, and it is wrong. `OidcTokenValidator`'s class
javadoc says the `aud` and `nonce` checks live in the OIDC module "so the bearer path stays
untouched", treating `aud` as OIDC-specific. It is not: it is RFC 7519 §4.1.3, and it is the
one thing the MCP specification requires of every resource server. Both OIDC
(`OidcTokenValidator.java:68`) and SAML (`SamlResponseValidator.java:161`) already enforce an
audience; the bearer path is the outlier, not the norm.

So: `tesseraql.security.jwt.audience` (string or list) on `JwtConfig`, checked in
`validateClaims` against both the string and array forms of the claim, with the algorithm
**lifted from `OidcTokenValidator.requireAudience`** rather than written again. And the
`exp != null &&` guard at `:112` stops treating an absent `exp` as "no expiry to check".

Two implementation traps, both recorded because the obvious code is wrong:

- `JwtConfig` is a positional record whose compact constructor applies every default by
  null-check. `requireExpiration` must therefore be a `Boolean`, not a primitive `boolean` —
  with a primitive, a caller passing `false` and a caller meaning "unset" are indistinguishable,
  and the zero value is the unsafe one.
- The claim side and the config side need two different coercions. `aud` is string-or-array in
  the token; `SecurityConfigFactory.parseJwt` (`:168-196`) reads every `jwt` key through
  `config.getString(...)`, through which a list cannot arrive at all.

`audience` is a **list**, and the token's `aud` — string or array — matches if any element is
present in it. That is the whole rule: TesseraQL neither implements nor requires the RFC 8707
`resource` request parameter, because what an IdP places in `aud` is its own configuration and
Keycloak, Entra and Okta all differ, many emitting only the client id.

The lint ships in the same slice and is an **error**, not a warning: declaring a JWT-validating
authentication mode without declaring an audience fails the build and the boot. A warning would
leave the hole open by default, which is the thing being fixed. `JwtConfigRules` already
navigates that config block. This is the one lint in the campaign that stops an existing app
from booting until its config gains a value, and that cost is why it belongs pre-1.0.

## Decision 2 — for MCP, TesseraQL is an OAuth 2.0 resource server, and says so

The current posture is deliberate and recorded in six places. `McpRouteBuilder`'s javadoc:
"Each tool runs its own route security, so there is no transport-level auth gate - discovery
is open and a tool that declares a policy enforces it on call." `docs/prompt-as-recipe.md`
argues it at length: "In the MCP ecosystem authorization sits at the transport (OAuth 2.1
over the HTTP transport) … TesseraQL has no transport gate on purpose — discovery is open and
each primitive carries its own security." `docs/app-mcp.md` states the consequence plainly:
"A tool with no security is public."

That reasoning is sound about *authorization* and silent about *authentication discovery*,
and the difference is what has changed underneath it.

**What the specification actually requires.** The MCP authorization model turned over exactly
once, at revision 2025-06-18, which reclassified MCP servers from authorization servers to
OAuth 2.1 **resource servers** and deleted the `/authorize`, `/token`, `/register` fallbacks.
2025-11-25 and the current 2026-07-28 kept that split and added only client-side machinery.
The server's entire obligation set is four items and has been stable across all three:

1. implement RFC 9728 Protected Resource Metadata, with `authorization_servers` carrying at
   least one entry — MCP adds that requirement; RFC 9728 itself marks the field optional;
2. expose it through the `WWW-Authenticate` `resource_metadata` parameter on 401 **or** the
   well-known URI (2025-06-18 required the header; 2025-11-25 relaxed it to "one of");
3. validate the token, including that it was issued for **this** server as audience;
4. never pass the inbound token through to an upstream API.

RFC 8414, RFC 7591 and RFC 7636 are obligations of the authorization server and the client.
Nothing requires an MCP server to run an authorization endpoint, a token endpoint, or a
registration endpoint — and RFC 7591 dynamic client registration has since been deprecated.

The whole authorization chapter sits behind a gate: "Authorization is **OPTIONAL** for MCP
implementations. When supported: Implementations using an HTTP-based transport **SHOULD**
conform to this specification." So skipping metadata violates a SHOULD. But items 3 and 4 are
unconditional MUSTs the moment a server accepts a bearer token at all — and today TesseraQL
accepts none, because `TesseraqlRuntime.java:1133` constructs the handler with a null
authenticator, which makes `McpHttpHandler`'s entire 401 path dead code in the runtime.

**Why the current posture is not enough, on its own terms.** The argument assumes a client can
authenticate if it wants to. It cannot. The `WWW-Authenticate` header the handler would send
is the bare string `Bearer` — no `realm`, no `error`, no `resource_metadata` — so a conformant
client has no way to discover which authorization server issues tokens for this resource. Open
discovery is a choice; *undiscoverable* authentication is not a choice, it is an absence.

**The decision.** Add a transport gate and the metadata that makes it usable, without demoting
per-primitive policy. Concretely: `tesseraql.mcp.auth` reusing the route `auth:` value
vocabulary (`public` by default, so nothing changes for an app that does not opt in), a
canonical `tesseraql.mcp.resource` identifier that is both the metadata `resource` field and
the required `aud` for tokens at this endpoint, and a metadata document served at the RFC 9728
path. Per-primitive `auth:`/`policy:` continue to run underneath, unchanged, on all four
document kinds.

**Serve both discovery mechanisms.** The specification has allowed either the challenge or the
well-known URI since 2025-11-25, but the clients this is for — Claude and ChatGPT — need the
challenge: Claude asks for a `401` carrying `WWW-Authenticate: Bearer resource_metadata=…`,
treats well-known probing as a fallback, and does not honour the header on a `200`. Serving
both costs one route and one header. Two client behaviours also bind the shape:
`authorization_servers` is an ordered list of which Claude reads only the first entry, and
neither vendor documents support for revision 2026-07-28 — both reference 2025-11-25, which is
the negotiation target worth having rather than the current revision.

Four more client behaviours constrain the implementation, all from Claude's connector
documentation and all easy to get wrong:

- The metadata's `resource` field **must match the MCP server URL exactly as the user typed it
  into the client, including the path component**. For TesseraQL that is the base path plus
  `/_tesseraql/mcp`, which makes `tesseraql.mcp.resource` derived-by-default rather than
  free-form, and ties it to `docs/base-path.md` rather than merely colliding with it.
- The `resource_metadata` URL **need not be on the MCP server's origin** — any HTTPS location
  serving the document will do. That is the escape hatch for the RFC 9728 path-insertion rule
  noted above: a runtime behind a base path can point the challenge at a location it can
  actually serve, instead of needing to own `/.well-known/*` at the root.
- The probe order, when the challenge is absent, is
  `/.well-known/oauth-protected-resource/<mcp-path>` **first**, then the bare
  `/.well-known/oauth-protected-resource`. Serving only the bare form makes the fallback miss.
- A pure `client_credentials` grant is **not supported** on the hosted Claude surfaces: every
  connection requires user consent. So headless agent-to-application access is not reachable
  through those clients at all, whatever this framework implements.

Two deployment facts belong in the eventual hosting documentation rather than in code: the
authorization server must be reachable from Anthropic's published egress range, and Claude
allows ten seconds for discovery, registration and token endpoints.

**An intranet deployment does not need any of this, and that bounds when the work is due.**
The dividing line between clients is not the transport, it is which side opens the connection.
A remote connector is fetched *from the vendor's cloud* on every Claude surface — claude.ai,
Claude Desktop, mobile and Cowork alike — so a server on a private network, behind a VPN or
behind a firewall cannot be reached "even if you can reach it from your own machine". The same
shape holds for ChatGPT's hosted connectors and for both vendors' server-side API MCP tools.
No amount of metadata makes an unroutable host routable. Conversely a client that runs *on the
user's machine* — Claude Code, the Codex CLI, the ChatGPT desktop app, and Claude Desktop's
local-server mechanism — connects from that machine, so an intranet host is reachable, over
plain HTTP as readily as over stdio.

For an intranet runtime reached by Claude Code or Codex, therefore, the framework already works
today: the surface is at `/_tesseraql/mcp`, per-primitive `auth:`/`policy:` already runs because
`AppMcpServer.call` forwards the caller's `Authorization` header into each `direct:mcp.*` route,
and both clients can supply a fixed token (`claude mcp add --header`, `bearer_token_env_var`).
What such a deployment still needs from this campaign is not Decision 2 but the MCP security
defaults of open question 4, because discovery is open and read primitives have no floor —
"anyone on the intranet" is a smaller blast radius than "anyone", not a zero one.

Claude Desktop is the case that inverts. Its remote-connector path is cloud-fetched and so is
unreachable on an intranet, while its local-server path is stdio — the one transport TesseraQL
cannot serve an application's MCP surface over. So for Claude Desktop specifically, on a private
network, the stdio mode described below is not a cheaper alternative to OAuth; it is the only
route that exists.

Three things this decision explicitly does **not** do:

- **It does not make TesseraQL an authorization server.** Option (c) — full OAuth with dynamic
  client registration — was not merely deprioritised, it was removed from the specification.
  Recording that here so a future reader who finds the 2025-03-26 revision does not rebuild it.

  **That choice has a precondition the rest of this decision assumes, and it deserves stating
  plainly: the operator must already run an identity provider.** TesseraQL mints no tokens. It
  serves no `/authorize` and no `/token`; a repo-wide search finds neither, and the only token
  endpoint anywhere in the tree is the one `OidcHttp` *calls* on someone else's server. Its own
  login surface is `POST /_tesseraql/login`, which issues a **session cookie**, and the OIDC
  module is a relying party whose callback also ends in a cookie. A cookie is not a credential
  an MCP client carries.

  So an operator whose only identity source is TesseraQL's own IAM — its login screen, its user
  store, its realms — **cannot serve the hosted assistants through OAuth at all**, because there
  is no authorization server to name in `authorization_servers` and nothing that can issue an
  access token bound to this resource. The `auth: bearer` path validates a JWT; it never
  produces one. Such a deployment's reachable options are the locally-running clients with a
  fixed token, or the stdio route above — not this decision. Whether TesseraQL should ever issue
  tokens is a much larger question than MCP authorization and is not opened here.
- **It does not change stdio.** Every revision says "Implementations using an STDIO transport
  **SHOULD NOT** follow this specification, and instead retrieve credentials from the
  environment." `McpCommand.serveStdio` builds `StdioTransport` with no authenticator, and that
  is correct by the spec rather than by accident. What stdio is owed is a citation, not a gate.
  (`JwtMcpAuthenticator` is wired only on the CLI's **http** transport, which additionally
  refuses to serve non-loopback without one.)

**But stdio is a second route to the same goal, and it is currently closed.** Two of the named
clients — Codex and Claude Desktop — mount local stdio servers as a first-class option, and on
that transport there is no OAuth to implement at all. TesseraQL cannot serve an application's
MCP surface that way today: `McpCommand` builds `new McpDevTools(app, readOnly).toServer()`, so
the CLI's stdio path serves the framework's *development* tools, never the app's declared `mcp/`
documents. The application surface is assembled by `AppMcpServer.build(appName, apps, producer)`,
which is package-private, takes a `ProducerTemplate`, and has exactly one caller —
`TesseraqlRuntime.java:1130`. It therefore requires a started runtime with compiled routes,
which is the correct dependency and also the reason there is no stdio entry point.

That is worth pricing against Decision 2 rather than assuming OAuth is the only answer. A
`tesseraql mcp --app` mode that boots the runtime and bridges stdio to the same
`direct:mcp.*` routes would reach Codex and Claude Desktop with no metadata document, no
authorization server, and no new config surface — while reaching ChatGPT's hosted connector and
claude.ai still requires the full OAuth story, because neither offers a local transport. The two
are complements, not alternatives; the design records the choice rather than making it.
- **It does not add a JOSE dependency.** Every piece of the existing stack is JDK-only by
  written posture, and nothing in items 1–4 needs more than the `KeyFactory`/`Signature`
  primitives already in use.

**The RFC 9728 path is not appended, it is inserted.** The well-known segment goes between the
host and the path components of the resource identifier — so for a runtime behind a base path,
the metadata document does not live under the base path the way every other framework URL
does. That is the one place this decision collides with `docs/base-path.md`, and it is called
out here so the implementation does not quietly "fix" it into consistency.

## Decision 3 — a constraint failure classifies as a constraint failure on every supported dialect

`SqlErrors.byVendorCode` (`tesseraql-core/.../dialect/SqlErrors.java:47-55`) maps Oracle and
MySQL codes and no SQL Server code at all. The mssql-jdbc bytecode
(`SQLServerException.generateStateCode`) maps `{208, 515, 547, 2601, 2627}` to SQLState
`23000`, so a duplicate key reaches `byVendorCode(2627)` and falls to the generic
`INTEGRITY_CONSTRAINT`. `isUniqueViolation` therefore cannot return true on SQL Server.

The blast radius is the whole claim-and-dedup layer: `JobRepository.tryClaimFiring` (`:66-88`)
returns false only on `isUniqueViolation` and otherwise **throws**, so every losing node in a
cluster raises an error instead of skipping; `SamlReplayGuard`, `JdbcWebhookReplayStore`,
`JdbcDocumentSequences`, `JdbcEventChannelStore` and the SCIM services all sit on the same
predicate; and `TesseraqlSqlProducer.classifyCode` downgrades the duplicate-create contract
from its own code to a generic execution error, which `ErrorResponseRenderer` renders as 500
rather than 409.

There is a second, wider failure. Under `xopenStates=true` the same driver returns `42000` for
2601/2627 — and `classify` (`:23-40`) discards any SQLState outside class 23 at `:30-32`,
before the vendor code is ever consulted. The deadlock code 1205 degrades the same way, so
serialization-failure detection is lost too.

The fix is therefore two-part: teach `byVendorCode` the SQL Server numbers, **and** stop
`classify` discarding an inconclusive SQLState before consulting the vendor code — with one
constraint recorded, because getting it wrong is worse than the bug. `byVendorCode`'s
`default -> INTEGRITY_CONSTRAINT` is only safe today because `:30-32` guarantees it is reached
only from class 23. pgjdbc sets vendor code 0 on every `SQLException`, so opening that path
without changing the default would classify a PostgreSQL syntax error, a permission denial and
a connection failure as integrity constraints. **The newly reachable path must default to
`UNKNOWN`.**

`SqlErrorsTest` has rows for PostgreSQL, MySQL and Oracle and none for SQL Server. A unit row
is necessary and not sufficient: the evidence has to be a live claim round-trip in the gated
dialect suite, because this repo has already shipped two dialect bugs that only a gated run
caught.

## Decision 4 — a poll source consumes each file once, whatever the transport

`PollingRouteBuilder.endpointUri` (`:106-131`) appends `&readLock=changed` at `:110` to the
same option string for local, sftp and ftps alike. `readLock=changed` is a write-stability
check, not inter-process exclusion. On the local transport that is less bad than it sounds —
Camel's `FileChangedExclusiveReadLockStrategy` extends the marker-file strategy and does write
an atomic `.camelLock` — but `SftpChangedExclusiveReadLockStrategy` implements the interface
directly and takes no lock, so **on sftp and ftps there is no exclusion at all** and three
replicas polling one drop directory each import every file.

Nothing else covers it. The job claim in `tql_job_claim` is per *firing*, not per *file*.

The mechanism is Camel's `IdempotentRepository` SPI — five methods in `camel-api`, already on
the classpath — with a TesseraQL JDBC implementation behind it, reached through a declared
TesseraQL concept on `PollSpec` rather than a Camel-shaped YAML key. Two facts decide the
implementation and both were found in bytecode rather than documentation:

- **`readLock=idempotent` does not work here.** `sftp.json` lists it in the `readLock` enum
  because the option is declared on the shared endpoint configuration class, not because the
  remote factory implements it. `SftpProcessStrategyFactory.getExclusiveReadLockStrategy`
  handles only `none`/`false`, `rename` and `changed`; every other value falls through to
  `aconst_null; areturn`. Setting it would leave the route with **no** read lock — losing
  today's write-stability check and gaining nothing.
- **The consumer-level flag needs `idempotentEager=true`.** `GenericFileConsumer` branches on
  `isIdempotentEager()`, which defaults to **false**. The eager arm calls
  `IdempotentRepository.add(key)` and rejects a false return, which is atomic; the default arm
  calls `contains(key)` and adds only on completion, which is check-then-act — two replicas can
  both pass `contains` and both import the file. Specifying the flag without the eagerness
  would ship the same defect one option over.

So: consumer-level `idempotent=true` with `idempotentEager=true` and a shared JDBC repository,
**keeping** `readLock=changed` alongside it. The idempotent key must be name+size+modified, not
the default absolute path, or a partner legitimately re-sending a same-named file is silently
suppressed forever.

That last sentence is a user-visible semantics change and therefore needs a declared key, a
default, and a documented statement — a re-sent file is re-imported today.

## Decision 5 — a failed boot and a clean stop both release what they took

`TesseraqlRuntime.close()` (`:2666-2693`) calls `closeQuietly(pinningSource)` at `:2671` and
`closeQuietly(otelSdk)` at `:2672`, both before `camelContext.stop()` at `:2681`. The tracer
and meter bound into the registry wrap that same SDK, so every span and metric produced during
Camel's in-flight drain — the window the drain exists to make observable — lands in a closed
SDK and is dropped. Only the `RouteWatcher` close above them carries a comment explaining its
ordering; these two do not.

The boot-failure catch (`:1875-1880`) closes three TesseraQL objects and rethrows without
calling `camelContext.stop()`, so the objects registered through `addService` before
`context.start()` — `TopicNotifyBridge` (`:868`), `VertxPlatformHttpServer` (`:1087`) and
`PgNotifyListener` (`:1597`) — all leak. A boot that fails after `:1087` leaves a bound port.

Both are orderings, not mechanisms. This slice is half a day and is not gated on 1.0, but it
should land early anyway: every other slice observes a process that currently leaks a port on
boot failure.

## Decision 6 — a batch run has an owner and a bounded stop

Nothing in the repository references `ShutdownStrategy` or `shutdownNowOnTimeout`, so Camel's
45-second default with hard-stop-on-timeout is inherited unread, and `SchedulingRouteBuilder`
runs jobs inline on the route thread. `tql_job_execution` (`V1__framework_operations.sql:6-17`)
has no owner, node or heartbeat column, and `JobRepository.findRunning` (`:145-162`) selects on
`status = 'RUNNING'` alone. A replica killed mid-run therefore leaves a row that
`JobExecutor`'s overlap check (`:240-250`) treats as a live run forever, and `overlap: skip`
wedges.

Configure the shutdown strategy explicitly, with declared keys, so a graceful stop either
finishes a run or abandons it on the record. Add node identity and a heartbeat, and give
`findRunning` a liveness predicate.

**The heartbeat cannot be written only at the boundaries the cooperative-stop work already
polls.** Those are step and chunk-commit boundaries, so cadence is bounded by step duration,
not by a clock — and a job whose long step is a single non-chunk statement emits no heartbeat
for its whole runtime. A timer-driven heartbeat is required; boundary writes alone would make
the reaper kill live runs, which is the exact false positive the alert-only SLA decision
(`docs/jobs.md:588-595`) was written to avoid.

Being plain about the limit: configuring the drain is mitigation, not a fix. SIGKILL, OOM and
node loss strand rows at any timeout. The reaper in Decision 7 is the fix, and it is not a
Camel adoption.

## Decision 7 — one span, one identity, and trace context crosses the boundary

`CompositeTracer` takes its identity from the first delegate (`:66-74`, and the class javadoc
at `:9` says so outright), which is the `RingTracer`, whose ids are
`Long.toHexString(ids.incrementAndGet())` (`:37`); `OtelSpan` never overrides `context()`. The id in every log line and the id on the span exported to OTLP are therefore
unrelated values, and nobody can pivot from a log line to a trace. That makes the shipped OTLP
feature close to unusable.

**Changing the ring's id format does not fix this**, and the obvious fix is the trap.
`OpenTelemetryTracer.start` (`:46-57`) uses the supplied identity only as a key into its
`liveContexts` map at `:55`; the span itself is created by
`tracer.spanBuilder(name).setParent(parentContext).startSpan()` with **SDK-generated** ids. A
W3C-shaped ring id would still not equal the exported span's id.

So the mechanism is an `IdGenerator` installed on the `SdkTracerProvider` and fed the ring's
ids, which makes the exported id and the logged id the same value in both directions. The
alternative — exporting the ring id as a span attribute — was rejected because it only lets a
reader go from a trace to the logs, and the direction that matters in an incident is from a log
line to the trace. That choice forces the other half: `RingTracer` must mint W3C-shaped ids
(16-byte trace, 8-byte span, lowercase hex) rather than a counter, which reaches every
structured log line through the MDC, the ops console's trace pages, and the ring's own storage.

Two neighbours ship with it. Suite mode has no tracing at all: the host-addressing overload
`TesseraqlRuntime.start(Path, int, String, String)` passes `NoopTracer.INSTANCE` at `:164` —
in `TesseraqlRuntime`, not in `MultiAppHost`, which has zero tracer references — and
`OpsDashboard` is built from `tracer` rather than `effectiveTracer` at `:1055-1057`, so the
console's trace pages are permanently empty behind `tesseraql host`. And nothing propagates:
`W3CTraceContextPropagator` ships in `opentelemetry-api` on the classpath with zero references
in the repository, `OpenTelemetrySupport.otlp` never sets a propagator, and `RouteTelemetry`
calls the no-parent `start(name)` overload. An app is always a trace root, and every outbound
call through `HttpCallClient` starts a fresh downstream trace.

Propagation must follow the identity work, not precede it: emitting a `traceparent` built from
a hex counter produces a malformed header.

## Decision 8 — the framework stops attesting a gate that does not run

`FrameworkSurfaces` lists `mcp.endpoint.post/get/delete` under `PROCESSOR_ENFORCED` with the
reason "McpHttpHandler calls McpAuthenticator with the Authorization header", while the runtime
passes `null`. `exempt()` (`:81-83`) is pure map membership, so `FrameworkSurfaceGuardTest`
cannot catch the divergence.

Move the three entries to `PUBLIC_BY_DESIGN`, carrying `McpRouteBuilder`'s own truthful
wording, and make the guard falsifiable: for each `PROCESSOR_ENFORCED` entry, drive an
unauthenticated request at the mounted route and require 401 or 403. On today's tree the three
surviving entries pass; putting `mcp.endpoint.post` back was run as an experiment and the guard
fails it with `answered 202` — which is the point, and 202 rather than the 200 first assumed,
because the endpoint accepts the JSON-RPC message rather than merely answering it.

A probe needs the verb, since all three surviving entries are POST and a probe without one gets
405 and proves nothing. Take it off the mounted route rather than recording it beside the reason:
the rest DSL runs with `inlineRoutes(true)`, so the definition carrying the `routeId` is the same
one whose input is `rest://<verb>:<path>`. Writing the verb into `FrameworkSurfaces` would add a
second source of truth for the mounting, inside the registry whose whole purpose is to stop that
kind of claim drifting from the runtime.

This lands regardless of whether Decision 2 is scheduled. A false attestation and a missing
gate are two different problems, and the record should be honest either way.

## Decision 9 — camel-health is a signal, and readiness costs one probe

`OpsDashboard.health()` (`:150-174`) calls `traceMetrics()` at `:151` and again through
`alerts()` at `:201`, and probes every datasource — on every poll of an endpoint that is
unauthenticated by design. Memoize the roll-up behind a short TTL.

`camel-health` is on the compile classpath, arriving transitively through `camel-core` and
`camel-ftp-common`, with zero references. It contributes what `OpsDashboard` structurally
cannot compute — a route's `ServiceStatus`, a consumer that has stopped — because only Camel
knows it. **Adopt it as a detail contributor and an alert source, never as the 503 gate:**
`DefaultHealthCheckRegistry`'s constructor sets `initialState` to `DOWN`, so a healthy consumer
that has not polled yet reports DOWN, and wiring that into a readiness probe produces a
guaranteed boot-time blackout on exactly the file and SFTP sources the adoption is for. Route
and consumer checks also duplicate each other, and `tesseraql-ops-ui` has no Camel dependency,
so the projection needs a supplier hook rather than a direct reference.

Separately: `camel-main` is declared at `tesseraql-camel-runtime/pom.xml:140-143` with zero
references anywhere. Removing it is a clean subtraction that also closes the `camel.server.mcp*`
door structurally — today the only thing keeping those properties inert is that camel-main's
bootstrap never runs. Note the honesty limit: after this, the barely-used `camel-health` is
declared while the heavily-used `camel-core` still arrives transitively through
`tesseraql-compiler`.

Finally, no JVM, GC, thread or Hikari pool metric exists anywhere — `MemoryMXBean`,
`GarbageCollectorMXBean`, `ThreadMXBean` and `HikariPoolMXBean` have zero occurrences in any
main source. Sample them into the existing meter and Prometheus rendering. JDK-only, matching
the posture that produced the hand-rolled meter in the first place; this is the one signal
`camel-micrometer` would genuinely have added, and it does not need Micrometer.

## Decision 10 — a signed SAML element is structurally unambiguous, not carefully read

These two arrived from the authorization-server research
([authorization-server.md](authorization-server.md) open question 4) rather than from the Camel
audits, and they belong here because that document is deferred and these are a few lines each.

`SamlResponseValidator` is immune to the 2018 attribute-truncation class, but **behaviourally**:
`text()` calls `getTextContent()`, which concatenates text children and excludes comments. A
refactor to `getFirstChild().getNodeValue()` would silently reintroduce the whole class with
every existing test still green, because nothing tests the property. Probed on a stock parser the
two accessors genuinely differ on the same payload — one returns the full signed string, the
other the attacker's truncation.

A regression test is the obvious fix and the weaker one. Copy Shibboleth's answer instead:
**reject a comment or CDATA child inside a signed SAML element at parse time.** OpenSAML has done
exactly that as a hard unmarshalling error since v3.4, after discovering that its own protection
was a parser default an integrator could switch off. That removes the accessor question
permanently rather than pinning one answer to it, and the regression test then guards a
structural property instead of a habit.

The neighbour is the same shape. `registerIds` marks every `ID` attribute as an XML id without
rejecting duplicates, while the signed element is resolved twice — once inside signature
validation and once by `getElementById`. They share one DOM registry so they should agree, but
XML enforces no uniqueness. The 2024–2026 record says this is the class worth spending the lines
on: what keeps breaking SAML implementations is two answers to "which element was signed"
disagreeing, including through parser differentials where the component doing the cryptography
and the component feeding the application build different trees from identical bytes.

Both are refusals at parse time, both are pre-1.0 because each adds a wire-visible rejection, and
neither depends on anything else in this campaign.

## Slices

| # | Slice | Pre-1.0 | Notes |
| --- | --- | --- | --- |
| 1 | Audience and expiry on the bearer path, plus the required-audience refusal | yes | Blast radius below; the only slice that fails an existing app's boot |
| 2 | The surface registry stops attesting a gate that does not run | no | Land first; it is half a day |
| 3 | SQL Server constraint classification, including the xopenStates path | yes | Gated dialect round-trip required |
| 4 | Per-file poll exclusion on every transport | yes | New key, new table, new migration |
| 5 | Shutdown ordering and boot-failure teardown | no | Land early regardless |
| 6 | MCP transport gate: `tesseraql.mcp.auth` and audience binding | yes | Depends on slices 1 and 12 |
| 7 | MCP Protected Resource Metadata and a conformant challenge | yes | Depends on slice 6 |
| 8 | Node identity, heartbeat, and a bounded stop | yes | Timer-driven heartbeat, not boundary writes |
| 9 | The reaper, and `overlap: skip` asking whether the owner is alive | yes | Depends on slice 8 |
| 10 | One span identity via an `IdGenerator`, W3C-shaped ring ids, and suite-mode tracing | yes | Reaches every log line and the console's trace pages |
| 11 | Readiness memoization, camel-health as a signal, camel-main removed, JDK gauges | yes | Two new keys (readiness TTL, ring capacity); dist-jar boot check required |
| 12 | MCP security defaults: a floor for primitives that declare none | yes | Open question 4's mechanism; the only slice an intranet deployment needs. Ships with [session-token-exchange.md](session-token-exchange.md) |
| 13 | SAML parse-time structural hardening | yes | Decision 10; independent of everything else here |

The numbers run in decision order, not schedule order — §Order carries the schedule. Slices 12
and 13 were added after the first draft: 12 because open question 4 closed on a mechanism that no
slice implemented, and 13 because Decision 10's two items had no home in either document.

W3C trace propagation is deliberately **not** a slice here. It is a new capability rather than
a repaired one, it must follow slice 10, and it carries a trust decision — an attacker-supplied
`traceparent` writes into the trace tree — that belongs with the reverse-proxy posture work.

Four orderings are forced: slice 1 before slice 6, because the gate validates an audience through
the bearer path; slice 10 before propagation; slice 8 before slice 9; and slice 11's
readiness fix before any camel-health adoption, since a gate multiplies whatever the probe
costs. The often-claimed slice 3 → slice 9 edge is **not** forced — `tryClaimFiring` is already
broken on SQL Server and the reaper does not consume the claim path — but the two are release-
gated together, because shipping a reaper that claims cluster safety on SQL Server would be a
false claim.

**Slice 1's blast radius is the campaign's largest and is not one day.** Seven example apps
configure a `jwt:` block, fourteen files declare `auth: bearer`, the scaffolder template emits
the block at `AppScaffolder.java:321`, scaffold-demo must be regenerated with
`-Dtesseraql.scaffold.regenerate=true` and never hand-edited, and 28 test sources mint or
configure JWTs.

Every slice that adds a key triggers the reference regeneration ritual — `docs/reference-config.md`
is generated and counts its keys — and every new error code needs `ErrorIndex` registration
plus the errors-reference regen. Slices 3, 4, 8 and 9 each require a gated dialect run before
the release that carries them, not merely a local container run.

## Order

Five waves. The waves are the schedule; the slice numbers above are not.

**Wave A — the half-days (slices 2, 5, 13).** Nothing here adds a config key or changes a
contract, and slice 5 earns its place first for a practical reason rather than a principled one:
the tree currently leaks a bound port on every failed boot, so every later slice's own
experiments hit it. Slice 2 makes the surface registry honest, and slice 13 converts a
behavioural SAML defence into a structural one.

**Wave B — the security hole (slice 1), alone.** The highest-value change in either document, and
the prerequisite for the MCP work. It runs alone because its fan-out reaches every gallery app,
the scaffolder template and 28 test sources, so anything else in flight collides with it.

**Wave C — cluster correctness, as one release train (slices 3, 4, 8, 9).** These are batched
because each demands a gated dialect run before the release that carries it, and one run covers
all four. Three of them carry migrations. Slice 8 precedes slice 9; slice 3 is release-gated with
slice 9 for the reason recorded above.

**Wave D — observability (slices 10, then 11).** Slice 10 adds no key and reaches further than
anything else in the campaign — every structured log line, the MDC, and the console's trace
pages. Slice 11's readiness memoization is small enough to pull forward into wave A if an
unauthenticated endpoint doing real work per poll is judged urgent; the camel-health adoption and
the JDK gauges are not.

**Wave E — MCP (slice 12, then the session-to-token exchange; slices 6 and 7 deferred).** The
transport decision closed at open question 10: tokens minted from an authenticated session reach
every client that runs on the user's own machine, and MCP authenticates such a call today without a
transport gate. Slice 12 ships first because it is the floor. Slices 6 and 7 wait for a deployment
that needs the hosted assistants, which nothing else reaches — the reasoning below is why they were
last in the order to begin with.
Decision 2 states that obligations 3 and 4 become MUSTs *the moment a server accepts a bearer
token at all* — and today it accepts none, because the handler is constructed with a null
authenticator. So once slice 2 lands, the framework is conformant by abstention, and its pre-1.0
obligation is discharged. What remains is a new capability, not a repaired one: it is the only
part of the campaign whose specification is still moving, the only part with no dogfood app, and
the only part that is useless to an operator who runs no authorization server. Everything else
repairs a shipped feature.

Two things belong inside wave E rather than after it. The **stdio-versus-OAuth decision**
(open question 10) is a gate on the wave, not a footnote to it — the answer changes whether
slices 6 and 7 are built at all for the deployment shapes that exist today. And the **companion
authorization-server documentation** ([authorization-server.md](authorization-server.md), "What
to do instead", item 3) ships with slices 6 and 7 rather than later, because without an
authorization server to name in `authorization_servers` those slices serve nobody.

## Traps

Recorded because each was a fix that looked correct and was not. The rule they share: **the
catalogue is the advertisement; the bytecode is the contract.**

1. `readLock=idempotent` on `sftp`/`ftps` — advertised in the component catalogue, unimplemented
   in the remote strategy factory, silently returns no lock (Decision 4).
2. The consumer-level `idempotent` flag without `idempotentEager=true` — check-then-act, so N
   replicas still import the same file (Decision 4).
3. Reshaping the ring tracer's id format — changes the format only; the exported span still
   carries SDK-generated ids (Decision 7).
4. Opening `classify`'s vendor-code path without changing `byVendorCode`'s default — pgjdbc
   reports vendor code 0 on everything, so unclassified PostgreSQL errors become integrity
   constraints (Decision 3).
5. Adopting `camel-health` as the readiness gate — `initialState` defaults to `DOWN`
   (Decision 9).
6. A heartbeat written at step boundaries — cadence bounded by step duration, so the reaper
   kills live runs (Decision 6).

## Lint

Numbers assigned at implementation from the registry; described here by meaning. Each entry names
the slice that ships it, because the first draft left two of them without one.

- **SEC, error (build and startup)** — *slice 1.* A JWT-validating authentication mode is configured and
  `tesseraql.security.jwt.audience` is absent. The message names the risk, not the rule: with
  an external `jwksUri` and no declared audience, a token that IdP minted for another relying
  party is accepted. An error rather than a warning because a warning leaves the hole open by
  default (open question 1), which makes this the one lint in the campaign that fails an
  existing app's boot until its config declares the value.
- **SEC, warning** — *slice 1.* A document declares an authentication mode other than `public` and no
  policy. It authenticates the caller and then authorizes nothing, which in the YAML is
  indistinguishable from a governed route. (Note the precedent does not carry here:
  `docs/prompt-as-recipe.md` argues that an MCP *read primitive* need not declare a policy,
  which is a different shape from authenticate-then-nothing.)
- **SEC, error** — *slice 6.* `tesseraql.mcp.resource` is not a canonical resource identifier. The
  specification makes the invalid forms explicit: a scheme is required, a fragment is
  forbidden, a trailing slash is not canonical.
- **MCP, error (build and startup)** — *slice 6.* `tesseraql.mcp.auth` is not `public` and neither
  `tesseraql.mcp.resource` nor `tesseraql.security.jwt.audience` resolves. A gate cannot
  validate audience without knowing what this server is called; the MCP key inherits the JWT
  one when omitted (open question 5), so this fires only when both are absent.
- **MCP, error (build and startup)** — *slice 7.* `tesseraql.mcp.auth` is not `public` and no
  authorization server resolves from either `tesseraql.mcp.authorizationServers` or
  `tesseraql.security.jwt.issuer`. The metadata document would be non-conformant.
- **MCP, warning** — *slice 12.* An MCP read primitive declares no `security:`, the MCP security defaults
  supply none, and `tesseraql.mcp.auth` is `public`. Fires only when nothing at all gates the
  call. Read primitives get no floor today, and unlike HTTP routes they never receive
  `security.defaults.routes`, because MCP documents are loaded into their own collections and
  never reach `applySecurityDefaults` — which is why open question 4 gives MCP its own
  defaults block rather than widening the path rules.
- **YAML, warning** — *slice 4.* A poll source on a transport with no server-side exclusion (`sftp`,
  `ftps`) declares no exclusive-consumption store. Every replica will import every file.
- **BATCH, error** — *slice 8.* A declared liveness window shorter than the heartbeat interval, which
  would reap runs that are alive.
- **BATCH, runtime status reason** — *slice 9.* An execution was reaped because its owner stopped
  reporting. Deliberately distinct from a failure the job itself produced, so the console and
  the alert set can tell them apart.
- **SEC, runtime 401** — *slice 1.* Token audience does not include this resource. Its own code rather
  than folding into the shared authentication failure, under the one-meaning-per-code rule.
- **SEC, runtime 401** — *slice 1.* Token carries no `exp`.
- **SEC, runtime rejection** — *slice 13.* A comment or CDATA node appears inside a signed SAML
  element. Its own code rather than the shared assertion-invalid one, because the operator-facing
  meaning is "this response was shaped to be read two ways", not "this response failed
  validation".
- **SEC, runtime rejection** — *slice 13.* A SAML document carries two elements with the same `ID`.

Slices 2, 5, 10 and 11 need no codes: they change an attestation, a shutdown ordering, an
identity format and a set of metric names.

## Out of scope

- **An authorization server.** TesseraQL issues no access tokens and will not. The AS is a
  separate role that the specification places out of its own scope.
- **OAuth scopes as a policy dimension.** `Policy.Rule` supports role, permission and
  claim-name-plus-value; there is no scope concept anywhere in the codebase, and adding one is
  a policy-model change, not an authentication change. The 2026-07-28 requirement that servers
  account for scope hierarchies has no home here yet and is recorded as an author convention.
- **Targeting MCP revision 2026-07-28.** The authorization obligations are identical across
  2025-06-18 through 2026-07-28, so this design is revision-neutral. The transport is not:
  2026-07-28 removes protocol-level sessions and the initialize handshake and adds a mandatory
  `server/discover`. That is a separate decision, and it should be taken before the transport
  is touched rather than folded into an authorization slice. It is also not urgent: neither
  Claude nor ChatGPT documents support for 2026-07-28, and both reference 2025-11-25 — so the
  revision worth negotiating next is 2025-11-25, two ahead of the current `LATEST` rather than
  three.
- **Killing an in-flight statement.** Decision 6 bounds the stop and Decision 9's reaper
  records the outcome; neither interrupts a running JDBC call, for the reason
  `docs/jobs.md:588-595` already records.
- **A dogfooded MCP gallery app.** No gallery or bundled app declares an `mcp/` folder today,
  and the only exercise of the surface is a test fixture. It is a separate slice from any of
  these — which means the strengthened surface guard in slice 2 is, until then, the only thing
  testing that surface. Out of scope as a slice, but **not** as a precondition: wave E puts a
  security gate on that surface, so it lands with wave E rather than whenever. Shipping a gate
  for a surface no shipped app exercises is how a gate ships broken.

## Open questions

1. ~~**Is `aud` required, or checked only when declared?**~~ **Closed: required.** Declaring an
   authentication mode that validates a JWT without declaring `tesseraql.security.jwt.audience`
   becomes a build and startup refusal, not a warning — checked-when-declared leaves the hole
   open by default, which is the thing being fixed. This upgrades the lint in §Lint from a
   warning to an error and moves the cost into the slice: the scaffolder template must emit
   `audience`, scaffold-demo is regenerated with `-Dtesseraql.scaffold.regenerate=true`, and
   seven gallery apps, fourteen files declaring `auth: bearer` and 28 test sources are touched.
   Pre-1.0 is the only time that bill is affordable.
2. ~~**How is one span identity actually achieved?**~~ **Closed: the exported id and the logged
   id are the same value, in both directions.** That rules out the span-attribute option, which
   only makes logs findable from a trace and not the reverse. The mechanism is an `IdGenerator`
   installed on the `SdkTracerProvider` and fed the ring's ids, which forces the second half of
   the decision: `RingTracer` must mint W3C-shaped ids — 16-byte trace, 8-byte span, lowercase
   hex — instead of `Long.toHexString(counter)`. The blast radius is every structured log line
   (the ids reach the MDC through `TesseraqlProperties.TRACE_ID`), the ops console's trace
   pages, and the ring's own storage. It also leaves W3C propagation as a small follow-on
   rather than a rewrite, because the ids are then already wire-shaped.
3. ~~**What defines liveness for the reaper?**~~ **Closed: a timer-driven heartbeat with a
   liveness window.** Not a node registry with a process identity — stronger, but a larger
   piece of work than the defect warrants — and explicitly not heartbeat writes at step or
   chunk boundaries, whose cadence is bounded by step duration rather than by a clock. A
   declared liveness window shorter than the heartbeat interval is a config refusal. The limit
   is recorded rather than papered over: SIGKILL, OOM and node loss strand rows at any window,
   so the reaper is a recovery mechanism, not a correctness guarantee.
4. ~~**Should path-matched security defaults reach MCP documents?**~~ **Closed: MCP gets its own
   defaults mechanism**, not a widening of `security.defaults.routes`. The reason is that an
   MCP primitive has no URL path to match on, so reusing the path rules would leave the
   question "what does this rule match?" unanswerable. One detail remains for implementation:
   whether the MCP block is a single default applying to every MCP document, or is narrowable
   by `kind:`. Start with the single block; `kind:` narrowing is additive if it is ever wanted.
5. ~~**May `tesseraql.mcp.resource` and `tesseraql.security.jwt.audience` differ?**~~
   **Closed: one identifier per runtime.** `tesseraql.mcp.resource` inherits
   `tesseraql.security.jwt.audience` when omitted, and an app that genuinely wants a separate
   audience for its MCP endpoint declares both. This narrows the MCP config-refusal lint: it
   fires only when neither is set.
6. ~~**What counts as compliant audience validation against an IdP that does not honour RFC
   8707 `resource`?**~~ **Closed: the token's `aud` must intersect the declared audience list,
   and that is the whole rule.** `audience` is therefore a list, and the token's `aud` — string
   or array — matches if any element is present in it. TesseraQL does not implement or require
   the RFC 8707 `resource` request parameter; that is a client-and-AS concern, and demanding it
   would break against Keycloak, Entra and Okta, which differ in what they place in `aud` and
   often emit only the client id. What lands in `aud` is IdP configuration, and the
   documentation says so rather than implying the framework can enforce it.
7. ~~**Do the MCP clients that matter require the metadata document before attempting OAuth?**~~
   **Closed: the clients are Claude and ChatGPT, and for both of them the metadata document is
   the entire feature.** On the hosted surfaces there is no field anywhere to hand the client an
   authorization-server URL, so if OAuth is chosen, discovery is the only way in. Claude's
   connector documentation is explicit that without metadata "Claude never learns where your
   authorization server is, and the connection fails"; ChatGPT's developer-mode and Apps SDK
   surfaces have no manual entry and no API-key field either, though OpenAI never states the
   failure mode normatively, so that half is inferred from the absence of any alternative.
   Three consequences the design must carry:
   - **Serve the challenge and the well-known URI, not one of them.** The specification allows
     either since 2025-11-25, but Claude asks for a `401` carrying
     `WWW-Authenticate: Bearer resource_metadata=…` and calls well-known probing "a fallback" —
     and it does not honour the header on a `200`. Implementing both costs one route.
   - **`authorization_servers` ordering is significant.** Claude uses the first entry and does
     not fall back to later ones. The list is not a set.
   - **Neither vendor documents support for revision 2026-07-28**; both reference 2025-11-25.
     That lowers the urgency of the transport rewrite in §Out of scope and makes 2025-11-25 —
     not the current revision — the useful negotiation target. TesseraQL negotiates to
     2025-06-18 today, so the gap that matters for these clients is two revisions, not three.

   Two escape hatches exist and neither reaches both clients, which is why they are not the
   answer: an authless server works everywhere, and a fixed `Authorization` header works for
   Claude (beta, org-admin, allowlisted header names, not permitted for Directory listing) and
   for every API and CLI surface of both vendors — but ChatGPT's hosted connector and Apps SDK
   state flatly that they cannot present a custom API key. Full OAuth is the only configuration
   that authenticates against both hosted assistants.
8. ~~**Is SQL Server production-supported or portability-tested only?**~~ **Closed: production-
   supported**, so Decision 3 is a release blocker rather than a documented limitation.
   `README.md:29` and `docs/overview.md:56` both advertise "Four databases — PostgreSQL, MySQL,
   Oracle, and SQL Server" as a headline capability; the gated dialect suite is how that claim
   is tested, not a hedge on it. The same treatment is therefore owed to the other three SQL
   Server constraint classes (515 not-null, 547 foreign-key/check) and to 1205 under
   `xopenStates`, not only to the duplicate-key codes.
9. ~~**Does the readiness memoization TTL become a declared key or stay an internal constant?**~~
   **Closed: a declared key — and so does the trace ring capacity.** The readiness TTL joins
   the `tesseraql.diagnostics.*` family, and the hardcoded `RingTracer(100)` at
   `TesseraqlRuntime.java:123` and `:136` gets a key of its own, matching the
   `slowSqlCapacity` key eleven lines below it that has always been configurable. Both are new
   keys, so both are pre-1.0 and both trigger the reference regeneration.
10. ~~**Is the answer for MCP OAuth, stdio, or both?**~~ **Closed 2026-08-15: neither, first.**
    The answer is a session-to-token exchange plus the MCP security defaults — see
    [session-token-exchange.md](session-token-exchange.md). Tokens minted from an authenticated
    session reach every client that runs on the user's own machine, and MCP already authenticates
    and authorizes such a call without slice 6, because `AppMcpServer.call` forwards the
    `Authorization` header into each `direct:mcp.*` route. What slice 6 adds is discovery and a
    floor, and slice 12 supplies the floor on its own.

    **Slices 6 and 7 are deferred, not cancelled**, on conditions rather than on vibes: a
    deployment that needs claude.ai or ChatGPT's hosted connector, which no fixed credential
    reaches and which therefore has no alternative. They land with an authorization server to name
    in `authorization_servers`, the companion documentation, and an MCP dogfood app — without which
    a gate would ship over a surface no application exercises.

    Nothing pre-1.0 turns on the deferral. The specification's two unconditional MUSTs attach the
    moment a server accepts a bearer token at its MCP endpoint, and it accepts none; slice 2 made
    the attestation honest, which leaves the framework conformant by abstention. The original
    reasoning below stands as the record of what the choice was between.

    *(Original framing.)* Decision 2
    describes a `tesseraql mcp --app` stdio mode as a complement rather than an alternative and
    then declines to choose, which is defensible in a design document and not defensible in a
    schedule. The choice decides real work: stdio reaches Codex and Claude Desktop with no
    metadata document, no authorization server and no new configuration surface, and it is the
    **only** route that exists for Claude Desktop on a private network — while claude.ai and
    ChatGPT's hosted connector are reachable through OAuth alone. So the deployment shapes that
    exist today are served by different halves, and the ordering question is which half is
    built first, not which one is right. Answer this before slice 12 rather than after slice 7.
