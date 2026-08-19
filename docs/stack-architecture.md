# Stack architecture

Status: **designed 2026-08-16** — nothing shipped. This document records a chain of decisions and
the reasoning that forced each one; the implementation designs it calls for are named at the end.

This began as a narrow question — can an ordinary business user connect a chat client to an
application's MCP surface — and did not stay narrow. The answer required an authorization server,
which required deciding where identity lives, which required deciding what a deployment *is*. Each
step is small; the chain is not. It is written down together because reading the steps apart makes
each look arbitrary.

**The spine.** Reaching MCP from the clients people actually use needs OAuth discovery. Discovery
needs an authorization server to name. An authorization server that authenticates TesseraQL's own
users belongs beside TesseraQL's own login. Once it is one component serving several applications,
the *stack* — not the application — is the unit that gets deployed, and the framework's own surfaces
belong to the stack rather than being copied into every application.

## What this revises

| Document | What changes |
| --- | --- |
| [authorization-server.md](authorization-server.md) | Decision 1 ("neither build nor adopt, yet") is reopened. Its survey asked which library could be *embedded*; it never priced building a companion, which is what is now proposed |
| [app-isolation-model.md](app-isolation-model.md) | Decision 2's independent-hosting mode is dropped — a **shipped, documented** mode, not an unreachable one, see Decision 12; Decision 4 (per-app ops console) is reversed; ① stops being a deployment shape and remains only a mechanism. Its "what existed" survey is marked as the starting state it always was |
| [audit-hardening.md](audit-hardening.md) | Open question 10's deferral condition for slices 6 and 7 is widened — see Decision 2 |
| [session-token-exchange.md](session-token-exchange.md) | Decision 1's premise ("there is no private key anywhere in the tree, and there will not be one") does not survive Decision 8; its refusal of refresh tokens and of a revocation store is answered differently for a different audience — Decision 9 |
| [threat-model.md](threat-model.md) | Gains an explicit row accepting framework identification — see Decision 21 |
| [base-path.md](base-path.md) | An application's base path becomes catalogue-driven rather than mode-derived |
| [hosting.md](hosting.md) | Loses "The two modes" once independent hosting goes, and gains the stack's development loop — slice 3, over [cli-surface.md](cli-surface.md) |

## Decisions

### 1. The clients that matter connect from the user's machine, and that changes what is owed them

The dividing line between MCP clients is not the transport, it is which side opens the connection.
`session-token-exchange.md` Decision 4 already recorded that Claude Code, the Codex CLI and the
ChatGPT desktop app all connect from the user's machine, and that claude.ai and ChatGPT's hosted
connectors do not. That table is correct and worth restating, because it is repeatedly misread as a
statement about transports.

The consequence that was missed: **a client reachable by a fixed credential is not thereby served.**
The ChatGPT desktop app accepts a static `Authorization` header, so it is "reachable" — but the
token it would carry lives 15 minutes, and asking a business user to re-paste a credential four
times an hour is not a delivery. Reachability was the wrong test.

### 2. Slices 6 and 7 are owed to a case their deferral condition does not name

`audit-hardening.md` open question 10 defers the MCP transport gate and Protected Resource Metadata
until "a deployment that needs claude.ai or ChatGPT's hosted connector, which no fixed credential
reaches." The condition is written on reachability, per Decision 1.

**A general business user on the ChatGPT desktop app is a third case.** It is reached by a fixed
credential and is not usable with one. And because that client connects from the user's own machine,
none of the costs that made slices 6 and 7 expensive apply to it: no public HTTPS, no vendor egress
range, no ten-second discovery budget, no behaviour that can only be verified against a vendor's
cloud. An intranet authorization server the user's browser can reach is enough.

So the deferral condition gains a second clause: **or a deployment that puts MCP in front of users
who are not developers.**

### 3. Dynamic client registration is required, and pre-registration is not available

Verified 2026-08-16 against primary sources, because the whole shape of an authorization server
turns on it.

- The Codex and ChatGPT desktop configuration reference documents exactly four authentication
  fields for Streamable HTTP servers — `auth` (`oauth` | `chatgpt`), `bearer_token_env_var`,
  `http_headers`, `env_http_headers` — plus `mcp_oauth_callback_port` and
  `mcp_oauth_callback_url`. **There is no documented way to configure a pre-registered
  `client_id`.**
- `openai/codex#19154` ("cannot use pre-registered client identity") has been **open** since
  2026-04-23 and was still being asked about on 2026-08-03. Its thread carries reproductions
  against Salesforce, Slack, Snowflake and Meta Ads hosted MCP servers, all of which refuse DCR.
  `openai/codex#13200` is open on the same defect. A contributor prepared a fork implementation on
  2026-06-18; no merged pull request exists.
- On the other side, `anthropics/claude-code#38102` asked for exactly this and was closed
  **NOT_PLANNED** on 2026-05-05.
- **Neither `openai/codex` nor `anthropics/claude-code` contains a single issue or pull request
  mentioning CIMD.** The MCP specification deprecated DCR in favour of Client ID Metadata Documents
  on 2026-07-28, and no client implements the replacement.

Two things follow. The authorization server **must implement RFC 7591**, which both remaining
candidates provide. And Keycloak's recorded advantage of shipping *both* CIMD and DCR is worth
much less than it appeared, because CIMD is dead weight against every client this targets. The
deprecation clock (removal possible from 2027-07-28) is a watch item, not a schedule input.

One undocumented workaround circulates — an `[mcp_servers.<name>.oauth] client_id` key — reported
together with a redirect-URI defect, since Codex appends `/callback/<callback_id>` to the
configured callback. **Do not design against it. Do verify the redirect-URI shape empirically**
before finalising the authorization server's redirect validation; strict exact-match will fail.

*Measured 2026-08-19, and the last sentence was wrong in the right direction: Codex registers the
complete callback URI — default `http://127.0.0.1:<ephemeral port>/callback/<callback_id>`, or
`<mcp_oauth_callback_url>/<callback_id>` when configured — and sends the same URI at
`/authorize`, re-registering on retry with a new port. Strict exact match therefore works: the
appended path is registered too. Recorded in `token-issuance.md` open question 2 and its
Decision 5.*

### 4. TesseraQL builds an authorization server for its own users, and only for those

`authorization-server.md` Decision 1 surveyed candidates against one criterion: is there an
embeddable, maintained, non-Spring OAuth 2.1 library? Under that criterion Spring Authorization
Server falls in one line, Apereo CAS and Syncope are "not liftable", and the answer is no. The
survey of companion products asked a different question — which off-the-shelf identity provider to
adopt — and answered Keycloak.

**Neither question was "should we build a companion of our own?"** That is the third category, and
it wins on three points that adoption cannot buy:

1. **No second user directory.** TesseraQL already owns users, roles, realms, password change and
   session invalidation. Keycloak and Casdoor bring their own store, so users exist in both —
   authorisation uses locally-managed roles either way (`OidcUserLinker`), so the duplication buys
   nothing. An authorization server that authenticates against TesseraQL's own identity service
   leaves one directory.
2. **No theme treadmill.** Keycloak's recorded cost is FreeMarker themes, roughly 140
   `theme.properties` entries, **no theme backward compatibility by the maintainer's own
   statement**, a theme-affecting change in every 26.x minor, and about four minors a year. Owning
   the login page — which TesseraQL already does, on hc — makes that cost permanently zero.
3. **Multi-node works.** Keycloak cannot match "every replica identical, the database arbitrates":
   `--cache=local` across N nodes fails *silently*, making single-use tokens replayable and
   multiplying brute-force budgets. An authorization server designed stateless-JWT over the shared
   framework datasource inherits TesseraQL's model instead of fighting it.

The scope is the limit that makes this defensible: **an authorization server for TesseraQL's own
users.** No SAML brokering as a product, no general federation, no MFA. The moment any of those is
a requirement, Keycloak's argument returns intact, and a deployment that already has an identity
provider should keep using it (Decision 7).

The target is precisely the gap `authorization-server.md` named and left open — an operator whose
only identity source is TesseraQL's own IAM "cannot serve the hosted assistants through OAuth at
all, because there is no authorization server to name in `authorization_servers`."

*Campaign started 2026-08-19 — [token-issuance.md](token-issuance.md) is the implementation
design, revised that day to the stack as shipped (the surface runtime, the stack file's security
graft, the acting-role contract). Same-day review closed its open questions: two on decisions
(the `TQL-OAUTH` domain, the fate of `TQL-SEC-4146`), two on the Codex leg of the
connect-and-observe pass (exact-match redirect validation, absent `scopes_supported`), three on
reviewed recommendations (consent page in `auth-ui`, the exchange's member axis, placeholder
refresh lifetimes). Still owed: the RFC 8707 `resource` observation.*

### 5. Two candidates remain, and the choice is whether a second process is acceptable

Re-surveyed under the build framing rather than the embed framing.

| Candidate | Second process | User store | DCR | Distribution | Login UI |
| --- | --- | --- | --- | --- | --- |
| **Apache CXF `rs-security-oauth2`** | **not required** | none — TesseraQL's | yes | a dependency | reuse the existing one |
| **Spring Authorization Server** | required (JVM) | none — wired by us | yes | one JVM | ours to write |
| Ory Hydra | required (Go) | none — headless | lifecycle defects open | **per-platform binary** | ours to write |
| Casdoor | required (Go) | **its own → duplication** | yes | per-platform binary | Casdoor's |
| Keycloak | required (JVM) | **its own → duplication** | yes, plus experimental CIMD | one JVM | theme migration |

**Ory Hydra deserves the note it did not get first time**: headless is the *feature* here, not the
flaw, because the login and consent screens being ours is exactly what authenticating against
TesseraQL's identity service requires. It loses on distribution — a Go binary is a per-platform
artefact, the very property that made Keycloak preferable to zonky's per-platform archives — and on
open defects in the DCR management endpoints (`ory/hydra#4084`, `#4093`, `#4060`).

**CXF is the only candidate that needs no second process at all.** `camel-cxf-rest` 4.22.0 already
depends on CXF 4.2.3, so the version alignment exists; `OAuthDataProvider` is a seven-method SPI, so
storage is TesseraQL's own database and multi-node follows; `/authorize`, `/token` and `/register`
become ordinary compiled routes. Against it stands the CVE record `authorization-server.md`
documented — thirteen OAuth and OIDC advisories in 2026 — although the most recent one found
(CVE-2026-50629, June 2026) is log injection via an unsanitised `clientId`, fixed in 4.2.2, which
is a milder class than the earlier batch. **Re-verify against primary sources at implementation
time**; that document's own instruction applies with full force here.

`authorization-server.md` also recorded a third path that was never priced: taking CXF's domain
layer and writing the endpoints as compiled routes, inheriting reviewed protocol logic without the
JAX-RS runtime. **Measured against 4.2.3 on 2026-08-16**, and the measurement corrects that
document as well as pricing the path.

| Package | Classes | Touching `jakarta.ws.rs` |
| --- | --- | --- |
| `common` — `Client`, `ServerAccessToken` | 25 | 1 |
| `grants` — the protocol logic | 36 | 17 |
| `provider` — SPIs and JSON providers | 30 | 11 |
| `services` — the endpoints | 21 | 18 |

That document's claim that "the only JAX-RS leak in the grant SPI is a `MultivaluedMap` in one
signature" is **wrong on count and right on consequence**, and the consequence is what matters.
Across `grants` and `provider` there are roughly 130 references to `MultivaluedMap` and 6 to `Form`
— **data carriers**, not control flow — against **five** references in total to `Response` and
`WebApplicationException`, the only places the protocol logic reaches for HTTP. The `MediaType`,
`Produces` and `MessageBodyReader`/`Writer` cluster is the JSON provider layer, which this build
replaces regardless.

So the dependency the grant layer imposes is `jakarta.ws.rs-api` — **a 152 KB API jar** — on the
compile and runtime classpath. Not a JAX-RS runtime, not a servlet container, not the CXF bus.
Confirm its own transitive set at implementation time.

Two further findings narrow it. Of five grant families (`code`, `refresh`, `clientcred`, `owner`,
`jwt`) this needs **two**: `code` and `refresh`. `owner` is the password grant OAuth 2.1 removes,
and `clientcred` is unsupported by the hosted assistants in any case. And inside `code`, the classes
**not** wanted are `JPACodeDataProvider`, `JCacheCodeDataProvider` and
`DefaultEncryptingCodeDataProvider` — the storage and caching implementations, replaced by
TesseraQL's own `OAuthDataProvider`, and **the same ones carrying several of the 2026 advisories**.
The mitigation this decision assumed turns out to be structural rather than aspirational.

**The measurement also settles the choice between the two paths.** Using CXF's `services` as they
ship means 18 of 21 endpoint classes bound to JAX-RS, hence a JAX-RS runtime in-process — against
the JDK-only posture and against the axis just decided. The grant layer beneath our own compiled
routes is the path.

**The axis is settled: no second process.** So the shortlist is the two CXF paths — the endpoints
as they ship, or the grant layer beneath them with our own compiled routes — and Spring
Authorization Server is recorded as the option that was available and not taken, so that a later
reader knows the cost was weighed rather than missed. Choosing between the two remaining paths
needs the estimate open question 1 calls for, and is the subject of the implementation design named
at the end.

Taking CXF in-process means **taking on its defect history in our own address space**, which is the
price of the axis. Two things bound it: the storage and caching providers that carry several of the
2026 advisories are replaced by TesseraQL's own `OAuthDataProvider`, and the version must be tracked
closely rather than pinned and forgotten. Write that expectation into the campaign rather than
leaving it to a future reader to infer.

### 6. The authorization server and the resource server share a host, and that is the ordinary case

MCP permits it explicitly — a single server may be both roles; nothing requires the split. Before
2025-06-18 the specification *assumed* colocation. Four things need care and none of them blocks it.

**Issuer and resource are distinct URLs on one host.** `authorization_servers` carries **issuer
URLs, not embedded metadata**; the client still fetches each authorization server's own
`/.well-known/oauth-authorization-server`, and the value there must equal the `issuer` field in the
document it finds.

**The issuer is the stack origin.** `https://stack.example.com`, with no path component, so RFC
8414's path-insertion rule does not apply and the metadata sits at the bare
`/.well-known/oauth-authorization-server` — the simplest form and the one clients handle best. The
endpoints (`/_tesseraql/oauth/authorize`, `/token`, `/register`) are listed explicitly in the
metadata and need not share the issuer's path. **The framework's URL prefix is therefore irrelevant
to the issuer**, which removes one coupling that would otherwise have been discovered late.

**Audience validation becomes the only boundary.** With one host and one signing key, `aud` is all
that separates a token minted for the application's API routes from one minted for an MCP surface.
The machinery exists — `SecurityConfigFactory` refuses to boot without a declared audience
(`TQL-SEC-4048`) since the audience work — but it stops being defence in depth and becomes the wall.
Each MCP resource identifier must differ from the application's own audience, and a lint should say
so.

**One session serves both roles, which is the point.** `/authorize` asks whether the caller is
signed in, and the answer is the existing `tesseraql_sid` — no second identity-provider session, no
second cookie, no cookie contention. The authorization-code redirect lands on the client's own
loopback origin, which is cross-site but a top-level GET navigation, so `SameSite=Lax` is
sufficient.

RFC 9207 (`iss` in the authorization response) is cheap now and awkward later. Include it.

### 7. An existing identity provider coexists, and brokering through it is the best shape

Three cases, and only two work.

**A — the existing provider only.** `auth: bearer` with RS256 and a `jwksUri`, and the provider
named in `authorization_servers`. Works today. The built authorization server must therefore be
**off by default**, on the same reasoning `token.enabled` is off: a component that issues
credentials should exist because somebody decided it should.

**B — the authorization server brokers to the existing provider.** This is the best shape and
TesseraQL is unusually ready for it: `OidcRouteBuilder` is already a relying party running
authorization-code with PKCE against an external provider, and `auth: browser` is satisfied however
the user signed in — own login, OIDC or SAML all land on one session cookie. So `/authorize` needs
no new authentication path.

What it buys is larger than convenience. **It makes TesseraQL a DCR proxy**: dynamic registration
facing the MCP client, an ordinary pre-registered client facing the enterprise provider. Decision 3
established that Entra-, Okta- and Salesforce-shaped providers refuse DCR and that Codex and Claude
Code cannot pre-register — this configuration resolves that deadlock without touching the customer's
identity provider.

**C — both token issuers accepted in parallel. Not supported.**
`tesseraql.security.jwt` is a single application-wide block with one algorithm and one key source,
so two issuers cannot both be validated. Widening it to an issuer-to-key-source map is a separate
decision; case B removes almost all demand for it. **Out of scope, stated rather than discovered.**

### 8. The authorization server signs RS256, which overturns a premise

For a single application colocated with its resource server, HS256 would be legitimate and much
cheaper: MCP clients treat tokens as opaque, so the algorithm is entirely between the authorization
server and the resource server, and choosing symmetric removes JWKS publication, overlapping key
rotation and `kid` assignment — the exact list `authorization-server.md` said asymmetric issuance
would require.

**A stack forecloses it.** If every application in the stack holds the same symmetric secret, **any
application can forge a token for any other**, and `aud` is not a boundary when the signing key is
shared. RS256 with the authorization server holding the private key and the applications holding
only the published key is what makes the audience separation of Decision 6 real.

Process separation, if it is ever built (Decision 15), reaches the same conclusion independently:
distributing a shared symmetric secret across processes is worse than publishing a key set. Two
unrelated arguments landing on the same answer is the reason to trust it.

The consequence is stated rather than left implicit: `session-token-exchange.md` Decision 1 rests on
"there is no private key anywhere in the tree, and there will not be one." **There will be one.**
Its `TQL-SEC-4146` refusal — the exchange is offered to HS256 applications and refused to RS256 ones
— was correct under the premise that TesseraQL never issues asymmetrically, and must be revisited
rather than quietly kept.

### 9. Access tokens stay stateless and short; refresh tokens are stored and revocable

`session-token-exchange.md` answered this question differently — fifteen minutes, no refresh tokens,
and "a short default lifetime is the revocation story" — and it was right for what it served.
**The audience changed.** That endpoint serves a developer at a terminal who can re-run a command;
this serves a business user whose chat client holds a connection all day. Re-consenting four times
an hour is the problem this chain exists to remove, so an authorization server without refresh
tokens does not deliver. It issues them, with rotation and reuse detection — the machinery that
document deliberately avoided, taken up here because the case differs, not because the earlier
judgement was wrong.

Revocation splits the same way, and the split is what makes it affordable. That document rejected a
revocation store because "a token id checked against a revocation table … would put a database read
in front of every bearer request on every route." **That objection is exactly right for access
tokens and does not apply to refresh tokens**, which are consulted only when a client refreshes.

- **Access tokens**: self-contained, validated statelessly, short-lived. No store, no read on the
  hot path.
- **Refresh tokens**: stored, rotated on use, revoked on sign-out and on account disablement, with
  reuse detection retiring the whole chain.

The asymmetry that document recorded — a password change invalidates every session and does not
invalidate the tokens minted from them — narrows to one access-token lifetime instead of persisting
until each token expires.

### 10. Registration is open, so consent is mandatory and client metadata is untrusted

Decision 3 established that MCP clients cannot present an initial access token; they post their
metadata and expect a `client_id`. **Gating registration therefore means not being reachable at
all.** Registration is open.

Open registration without consent would hand the user's authority to anyone who can reach the
endpoint, silently. So **consent is mandatory**, and three consequences are easy to leave implicit
and expensive to discover:

- A client's `client_name` and other registration metadata are **display text chosen by the party
  asking to be authorised**. Render escaped, never trusted. A consent screen that presents an
  attacker's chosen name as though the framework vouched for it is a phishing surface.
- **Consent is recorded per client and per resource**, not per client. A stack hosts several
  applications with separate audiences (Decision 6); consenting to one is not consenting to the
  rest, and that separation is the whole reason the audiences exist.
- Consent is **revocable by the subject**, which the account surface (Decision 14) is the natural
  home for.

Open registration leaves a registration-spam surface. Bounding it belongs to the ingress under
Decision 13's division, not to closing the endpoint.

### 11. A token carries TesseraQL's own authority, scoped to one resource

Three questions that will otherwise be settled by whoever writes the code first.

**Where claims come from.** Under Decision 7 case B the authorization server authenticates through
an external provider and must still mint **TesseraQL's** roles and permissions rather than the
provider's assertions. `OidcUserLinker` already states that rule for federated logins; an
authorization server that passed provider claims through would quietly reverse it.

**How much authority one token carries.** A subject's roles are stack-wide; an application's policy
is not. A token minted for one resource carries what that resource's policy can read, not the
subject's authority across the stack. Decision 6 makes `aud` the boundary, and a token carrying
every application's roles would make the boundary narrower than the credential it guards.

**What to do with `scope`.** `session-token-exchange.md` observed correctly that `Policy.Rule` has
role, permission and claim dimensions and no scope concept, so a token carrying scopes would carry
something nothing reads. An authorization server cannot simply ignore the parameter — clients send
it, and Codex prefers server-advertised values when `scopes_supported` is present. The resolution is
to **advertise no `scopes_supported`, accept the parameter, and grant nothing on it**;
authorisation stays by role and permission rather than gaining a second vocabulary that duplicates
the policy engine. Revisit only if a client refuses to proceed without one — measurable in the same
connect-and-observe pass that open question 6 calls for.

### 12. Shared stack is the only deployment shape

`app-isolation-model.md` Decision 2 gave ② two modes. Independent hosting — `Host`-header routing,
per-host session cookies, per-application datasources — is intended for "unrelated apps, or apps
from different authors." It is dropped.

**A documented feature is being deleted, and this paragraph used to deny it.** It quoted
`app-isolation-model.md`'s "no CLI or plugin entry point, no user documentation" — a sentence that
document wrote about its own starting state and never updated once its follow-ups closed the gap.
Measured 2026-08-16: `HostCommand` is registered in `TesseraqlCli`, `tesseraql host --mode isolated`
runs, `hosting.md` carries a two-mode comparison table, the page is published, and
`reference-cli.md` has the row. Independent hosting is reachable, documented and shipped.

**The deletion is still right, on the ground the next paragraph gives** — development and production
parity — which never depended on how many callers the mode has. What changes is the accounting: this
removes a feature rather than tidying away an unreachable one, so it is a breaking change and is
recorded as one. Pre-1.0 that costs no migration path and no upgrade instructions; it does cost an
honest changelog line saying the mode is gone and why, and the removal of the mode table from
`hosting.md` rather than its quiet decay.

What *is* thin is the code: a `Mode` enum, a `hostToApp` lookup consulted first, and one conditional
in the base-path assigner. Small to remove is not the same as unreachable, and conflating the two is
how a shipped feature disappears without anyone writing it down.

**The gateway-less single-application shape goes too**, which is the larger half of this decision.
The argument is development and production parity: a team building several interlocking
applications must develop against the topology it deploys, and under the previous split it could
not. The concrete instance is this document's own subject — **the authorization server, MCP
discovery and the OAuth flow could not be exercised locally in the shape they run in production**,
which is exactly the class of defect that is most expensive to find late.

Two conditions make this affordable rather than punitive.

**Base path becomes catalogue-driven, not mode-derived.** `MultiAppHost.start` already takes a
function from application id to base path; today it reads `mode == Mode.SUITE ? PREFIX + appId :
null`. Reading the catalogue instead, defaulting to `/apps/<appId>/`, lets a one-application stack
declare `/` and serve at the root — the old shape, with no second mechanism and no branch in any
design that follows.

**Note 2026-08-16, because the implementation drifted from this sentence and had to be pulled back.**
"Declare" is load-bearing. The first CLI surface gave the running commands an `--app` flag that
*derived* the origin root from the shape of the directory it was pointed at, which is the same
second mechanism this paragraph rules out, arrived at from the CLI rather than from a `Mode` enum.
[cli-surface.md](cli-surface.md) Decision 1 has been amended to remove it: the running commands take
`--stack` only, an application home is a stack of one, and the origin root is what `tesseraql-stack.yml` says
it is — later narrowed further by Decisions 24 and 25: always a redirect — to the portal by default —
never an application.

**Decision 13 is a prerequisite, not a follow-up.** Routing every deployment through the gateway
before the gateway is transparent ships a regression.

① survives as a *mechanism* — it is how each runtime mounts framework surfaces — and stops being a
deployment shape. The two meanings have been conflated in conversation and must not be conflated in
the documentation.

### 13. The gateway aggregates internal applications; it does not stand guard

`MultiAppGateway` bounds request bodies at 10 MB by reading them fully into a `byte[]` before
forwarding, and bounds responses at 64 MB, aborting the relay mid-body when an undeclared length
runs past the limit. The comment states the justification plainly: *"Bounded, because this is the
front door."*

**It is not the front door.** Under Decision 12 it fronts applications the operator installed, in a
stack whose members are mutually trusted, behind whatever ingress the deployment already has. The
justification does not survive the repositioning, and the bounds are wrong as universal limits: a
10 MB ceiling caps every attachment and import, and a 64 MB ceiling **silently truncates exports**,
which is the precise failure mode the export pipeline was built to avoid.

Changes:

- **Request bodies stream**, replacing the buffer and the cap.
- **The response bound and its mid-body truncation are removed.**

This decision originally also kept **ingress header stripping**, on the reasoning that applications
may be trusted while callers are not. Implementation found that reasoning does not survive contact —
see the addendum.

Already correct, verified rather than assumed: the cookie path is `/` in stack mode by design
(`CookiePath`'s own documentation — "A shared stack wants `/`, because one sign-in reaching every
application *is* the mode"), and neither the client nor the requests carry timeouts, so downloads
and streams are not cut.

**The deliverable that makes "equivalent" true rather than hoped-for is a differential test**: issue
the same request against one gallery application's own port and through the gateway, and assert the
answers match. Add the cases a proxy breaks specifically — a large upload, a large download, an
event stream measured for arrival timing, a chunked response with no declared length, `HEAD`, `304`,
redirect `Location` values, and the `Path` attribute on cookies.

`hosting.md` gains the division: **the gateway routes, the ingress protects.** Body limits, rate
limiting and TLS termination belong to the reverse proxy every deployment already runs.

#### What the measurement found, and what it changed — 2026-08-16

This decision asked for one measurement before implementation: flush behaviour on the response copy,
because buffering there produces the hardest failure to diagnose — working, but late. **The
measurement found something worse, and three paragraphs above are wrong as written.**

The relay did not stream. `com.sun.net.httpserver` reads a response length of `0` as "chunked,
length unknown" and `-1` as **"no response body"**; the relay computed `-1` for an app that declared
no `Content-Length` and passed it straight through. **Every chunked answer lost its body** — 200,
the right headers, and nothing after them. That is every streaming export and every event stream, so
the sentence "responses already stream through a fixed buffer; only the ceiling goes" describes
something that was not happening. With the length corrected, the predicted defect appeared as
predicted: the copy loop never flushed, so all frames landed together at close. A third defect sat
beside them — the outbound client negotiated h2c with the app and the response headers were copied
into an HTTP/1.1 answer unchanged, putting the HTTP/2 `:status` pseudo-header on the wire as an
HTTP/1.1 field name.

**So the relay is `vertx-http-proxy` rather than a corrected copy loop.** Vert.x is already a compile
dependency through `camel-platform-http-vertx`, so this is one jar at a version the runtime already
resolves. Three defects in roughly forty lines, in the component Decision 12 puts in front of every
request in every deployment, is the argument: framing, flushing and protocol translation are what a
proxy library exists to have already solved. Two consequences are recorded rather than left to be
discovered:

- **`SKIP_HEADERS` goes entirely.** Its framing entries would corrupt a relayed body if applied on
  top of a proxy that owns framing, and its hop-by-hop entries are the library's — asserted rather
  than assumed, by an origin the test owns reporting that `te`, `trailer`, `connection`,
  `keep-alive` and `upgrade` never arrive. What remained after that was three headers nothing reads;
  a denylist with no live effect reads later as a control, so it is not kept.
- **The execution model inverts.** The old front ran a virtual thread per request, which made
  blocking safe by default. On an event loop a blocking call stalls every connection sharing the
  loop. Nothing on the path blocks today — routing is map lookups and the entitlement check reads an
  in-memory record — but that is now a rule the slices adding work here have to keep. In exchange a
  long-lived stream costs no thread rather than a parked one, which is a stronger form of what
  "long-lived streams do not exhaust a pool" was claiming.

**And ingress header stripping is removed, because it was breaking the feature it named.** The
gateway stripped the mTLS forwarded header each application declares. It stripped
**unconditionally** — there is no trusted-proxy concept anywhere in the tree — so the value the edge
had *just set* was destroyed along with a forged one, and mTLS forwarded-header authentication could
not work behind the gateway at all. Nothing caught it because `MtlsIntegrationTest` never goes
through a gateway. Decision 12 changes what that costs: while the gateway was one shape among
several this was "mTLS is unavailable in stack mode"; as the only shape it means
`SecurityConfigFactory`'s mTLS branch, `MtlsConfigRules`, `TQL-SEC-4061`, `trustBundle` PKIX
validation and a chapter of `authentication.md` all support something unreachable.

The duty is already assigned where it can be discharged. `authentication.md`'s trust contract reads:
*"the edge must overwrite (or strip) the `forwardedHeader` on every inbound request, and the runtime
must not be reachable except through that edge."* Duplicating it one hop later bought defence in
depth against an operator who has already lost — the network isolation the same contract requires —
and paid for it with the feature. Keeping the strip *and* the feature needs the gateway to know
which sources are trusted, which is a configuration this slice did not have. It arrives as
`--trusted-proxies`: name the edge's addresses and the header is stripped from requests arriving
from anywhere else, compared against the peer of the connection rather than a header a caller can
write. Empty is the default and strips nothing — reading "no edge named" as "strip from everyone"
is the unconditional strip this decision removed, restored as a default. This is the same division the decision is
drawing anyway: **the gateway routes, the ingress protects.**

**The front is HTTP/1.1 by default, and HTTP/2 is one switch that moves both hops.**
`com.sun.net.httpserver` spoke nothing else, so no client ever reached a hosted app over h2c through
the gateway; serving it is new behaviour and an operator asks for it (`--http2`).

The defect that made this look unshippable is worth recording, because three separate diagnoses of
it were wrong and each was reasoning ahead of a measurement that was cheap to take. An h2c front
logged an `IllegalStateException` on the event loop for every request, while the request itself
succeeded. The wrong answers, in order: that the outbound hop was HTTP/1.1 (the stack named
`HttpClientRequestImpl`, which serves *both* protocols and says nothing about which); that HTTP/2
omits `content-length` and large uploads were the casualty (an 8 MB `POST` arrives as
`length=8388608` and relays cleanly); and that no extension point could reach it (one could). What
the body lengths actually showed: **`GET length=-1`**. Over HTTP/2 a `GET` still ends its stream
with a data event, so the proxy relays a body of unknown length, and an unknown length on a method
that cannot carry a body is zero. Saying so — `Body.body(Buffer.buffer())` in a request interceptor
— is the whole fix. The pairing was never the problem: HTTP/1.1 in and HTTP/2 out is clean.

**And the deliverable as first written could not be built.** "Run one gallery application's
declarative stack twice" assumes that stack drives HTTP. It does not: every case kind `TestRunner`
supports — `sql`, `contract`, `validate`, `decide`, `notify`, `http`, `messages`, `transition`,
`dispatch` — evaluates in process against the app home and datasource, and `http` plans a route's
*outbound* calls. `testing.md` says so outright, describing a dispatch case as "the button the UI
actually calls, asserted without HTTP". Run twice, such a stack compares an in-process evaluation
with itself and passes with the gateway switched off. The wording above is corrected to what the
deliverable was reaching for: the same request issued at both ports, over real HTTP, answers
compared.

### 14. Framework surfaces belong to the stack, and the ones that stay per-application delegate

Today five framework applications are mounted into every runtime through `ServiceLoader`. Under
Decision 12 that means N copies of surfaces whose state is stack-wide.

**The identity surfaces become one, and this is not a reversal of anything.** `auth-ui`, `account`
and IAM Admin operate on the shared framework datasource, and a stack has one session, one sign-in
and one user store. Five "change my password" pages is the anomaly. Together with the authorization
server they form the stack's identity surface, and `app-isolation-model.md` Decision 1's criterion —
system applications must share the host's state — reads the same way once the state in question is
stack-wide.

**The ops console and Studio become stack-level shells with an application switcher, which does
reverse `app-isolation-model.md` Decision 4.** That decision made the console per-application and scoped, deliberately giving
up the single cross-application screen. Its three justifications each weaken under Decision 12:
"traces need no cross-runtime aggregation" holds only while there is nowhere to aggregate to;
"*which runtime's ops do I open* stops being a question" is answered by a switcher; and "the console
stops behaving differently depending on whether the deployment shares a database" is moot when there
is one shape. The reversal is legitimate, and the document must record what changed rather than
simply flipping.

**They aggregate over runtimes, not over databases.** Reading each application's datasource
configuration was considered and rejected on four grounds, one decisive: **`RingTracer` is an
in-memory ring inside each runtime, so a database connection cannot serve the trace pages at all** —
and those are the pages where a switcher is most wanted. The others: N connection pools held by one
application; `app-isolation-model.md` Decision 3's rule that an application's configuration is not
the authority on its database connection, so a second reader can resolve differently; and scoping
and permissions staying where they are implemented.

A consequence that is easy to miss: **a stack-level Studio can edit any application's source.** The
permission model changes shape. `ops.app.<name>` reverts from "the permission to open an
application's console" toward "which applications appear in the switcher", and Studio needs
per-application edit authorisation on the switch rather than a single "may open Studio" role. The
audit trail widens correspondingly.

This changes what a framework application *is* — from a bundle mounted into a runtime to a component
hosted by the stack that talks to runtimes — so the hosting mechanism changes with it, from
`AppSourceProvider` discovery to being hosted alongside user applications.

One collision class disappears as a by-product: with framework surfaces at the gateway root and user
applications under `/apps/<appId>/`, `requireNoRouteConflicts` has nothing left to catch between
them.

**Implementation design: [stack-shells.md](stack-shells.md)** (2026-08-18) — the ops shell
(slice 7), the authorization model that closes open question 4 (marked atoms
`tql.<family>.<verb>.<name>` over dot-free names, designed persona-first: business users,
developers, operators), the identity
remainder of Decision 24's slice 4, and the authenticated deploy surface runtime-replace.md
deferred to the grants work. The Studio shell (slice 8) is deliberately left to its own
design on the delegation pattern established there. **Its slice 1 — the atom grammar and the
ops shell with its switcher, delegation and per-member canary entries — is shipped**; the
identity remainder and the deploy surface follow as its slices 2 and 3.

### 15. Process separation is not built, and the boundary is kept shaped so it stays available

`app-isolation-model.md` lists splitting ② across processes as out of scope with the note that "the
measurements assume one JVM." **That wording bakes in an assumption where a deferral was meant**,
and it is amended: the delegation boundary is API-shaped, so the split remains reachable; it is
simply unbuilt.

The ordering argument for doing it first was that it would *enforce* the discipline. **That
enforcement already exists and was verified**: `tesseraql-studio`, `tesseraql-ops-ui` and
`tesseraql-identity` depend on `tesseraql-core`, `tesseraql-yaml`, `tesseraql-operations` and
`tesseraql-security` — **none of them depends on `tesseraql-camel-runtime`**, so none of them can
hold a runtime reference. It will not compile. The two framework applications that live inside the
runtime module, `AuthUiAppProvider` and `AccountAppProvider`, are exactly the ones Decision 14 moves
out, so the remaining boundary is created by the work itself.

Against doing it first: each process pays the full JVM baseline (22 MB heap, 29 MB metaspace, about
2,200 ms) where an additional in-JVM runtime costs 8 MB, 0 MB and about 600 ms — a penalty paid on
every restart of the development loop that Decision 12 exists to protect. And interleaving process
supervision, port allocation, shutdown ordering and a new class of failure states with the largest
refactor in the framework makes failures unattributable.

The discipline the module boundary cannot enforce is *semantic*: delegation written as though it
cannot fail, cannot be slow and cannot be asynchronous. So **delegation is real HTTP over loopback
even within one JVM** — the same hop the gateway already performs for every user request. Then
failure is a state that exists and is tested from the first day, and process separation becomes a
change of address rather than a change of design.

Add a build check that the stack-application modules never acquire a dependency on
`tesseraql-camel-runtime`. It is permanent, free, and states the rule where it will be read.

**Revisit when** a stack needs one application's failure not to take the others down; or per-
application upgrade and canary reach production, where `AppUpgrader` and canary weights already
exist; or per-application resource limits are required. None of these is present today.

### 16. Settings only the host can know correctly belong to the host, and that includes migrating the shared schema

The framework already made this call once and did not generalise it. `CookiePath`'s own
documentation records the reasoning: "Only the component that starts the runtimes knows which of
those it is building, so it carries the value; **a configuration key was considered and rejected**,
an operator setting it wrongly getting either a silently unshared stack or a session offered to
every neighbour, neither of which announces itself."

**The rule that generalises it:** a setting belongs to the host when only the host can know it, or
when divergence between applications fails silently.

`MultiAppHost.start` passes an application home, a port, a base path and a cookie path — the last
two by this rule. Three more qualify and are not passed today:

- **`tesseraql.framework.datasource`.** A shared cookie reaching every application is worthless
  unless every runtime reads the same session store. Divergence presents as "signing in does not
  carry", which reads as a framework defect rather than the misconfiguration it is.
- **The external origin.** MCP's `resource` and the authorization server's `issuer` are absolute
  URLs, and Decision 6 requires `resource` to match what the user typed character for character.
  **An application cannot know its own external origin**; only the gateway does. This value exists
  nowhere today and Decision 18 cannot be implemented without it.
- **`security.jwt.algorithm`, `issuer` and `jwksUri`.** One authorization server means one issuer
  and one key set. `audience` stays per application — it is the boundary.

What stays per application is as much the point: business datasources, audiences, policies. This is
not a call to hoist configuration generally.

**And the framework schema is migrated once, by the host, before any runtime starts.**
`FrameworkMigrations.migrate` runs today from `TesseraqlRuntime.java:501` on every runtime start,
across two components with different homes — `security` (sessions) on the framework datasource and
`operations` on the business datasource. **The split is already half right**: `operations` is
per-application and stays there. `security` is stack-wide, so N runtimes take the lock on one
`tql_schema_history__security` in turn and N−1 do nothing. Flyway's lock makes that safe rather than
correct; its documented purpose is "serializing concurrent node startups", which anticipated
replicas of one application, not several applications of one stack.

So the host migrates `security` once, and **runtimes validate instead of migrating**, failing to
start when the schema is not at the version they expect.

**Status 2026-08-17: shipped.** The host migrates `security` before any runtime starts — on the
stack's own pool when `tesseraql-stack.yml` supplies one, otherwise through a migration-only pool
on the coordinate the applications agree on (TQL-APP-4211 is what makes "the first application's
coordinate" the stack's). Hosted runtimes migrate only their per-application `operations`
component and **validate** `security`, refusing on a mismatch with **TQL-APP-4214** — which is
also what refuses a canary whose framework-schema expectation is newer than what the host
migrated (Decision 29's validate-don't-migrate clause). Standalone starts keep migrating both
components themselves.

That last clause dissolves a guard this document previously wanted on its own. A runtime pointed at
the wrong framework datasource finds no migrated schema and **fails loudly at boot** instead of
producing a stack where sign-in silently does not carry. Validating rather than migrating *is* the
check.

Two implementation notes. The parameter list is already four and would reach eight; the host's
decisions want **one context object** rather than more positional arguments, which also gives the
tests that call `TesseraqlRuntime.start` directly a single place to change. And the host must
resolve a datasource configuration that may carry `${secret.…}` references, so **the secret
provider has to be reachable at host scope** — verify that before designing the hoist (open
question 3: yes, `SecretResolvers.discover()` is static and process-scoped).

**Status 2026-08-16.** `HostContext` exists, carrying the two settings a host can already answer:
the address the catalogue declares and the cookie path. It replaced the positional arguments and
the app-id-to-prefix function the host used to be handed, so the catalogue is now the single source
of an application's address.

**The remaining three were gated on a question this document did not ask: where does a host read its
own settings from?** `tesseraql.framework.datasource`, the external origin and the issuer/JWKS
triple all need a host-scoped source, and none existed —
[cli-surface.md](cli-surface.md) records the same gap from the other side for the gateway's port
("a stack-level configuration file does not exist yet"). **Decision 22 answers it**, and the
`security` migration hoist below is built on that answer.

### 17. The URL scheme is one rule applied at two scopes

```
/_tesseraql/login              stack identity
/_tesseraql/oauth/...          authorization server endpoints
/_tesseraql/account
/_tesseraql/iam
/_tesseraql/studio             application switcher
/_tesseraql/ops                application switcher
/_tesseraql/portal             the stack's portal; / is a 307 to it, or to root.redirect (D. 24)
/orders/...                    a user application (Decision 25; /apps/orders before it)
/orders/_tesseraql/mcp         that application's MCP surface
/.well-known/...               authorization-server and protected-resource metadata
```

The rule: **framework surfaces live under `_tesseraql/` relative to their scope.**

The prefix's original justification is gone — it existed because framework surfaces shared a flat
URL space with a root-mounted user application, and under Decision 12 they no longer do. It is kept
on three replacement grounds. The collision it prevented **still exists one level down**, inside
each application's prefix, where an application's declared routes share a namespace with the
framework surfaces belonging to that application; dropping the prefix at the stack level alone would
make the two scopes asymmetric for no gain. It keeps the framework's claim on root names at **two**
(`/apps/`, `/_tesseraql/`) rather than one per surface, permanently. And it leaves the rest of the
root to the operator, who may want `/health` for a load balancer.

**Amended by Decision 25**, which retires `/apps/` — the second and third grounds above described
what that prefix bought, and Decision 25 records what replaces each: the name grammar fences the
root, and the operator's health path is `/_tesseraql/health/live`, which the deployment image
already probes. The `_tesseraql/` half of this decision is untouched.

The leading `_` carries no protocol meaning — RFC 3986 lists it as unreserved — and follows the
convention of `/_next/`, `/_nuxt/`, `/_matrix/` and `/_ah/`: a reserved-namespace marker, a
collision probability near zero because business domains do not name path segments with a leading
underscore, and a single prefix that separates framework traffic from business traffic in logs and
metrics.

### 18. MCP is per application, with no stack-level aggregate

Each application serves its own surface at `/apps/<appId>/_tesseraql/mcp`, with its own `resource`
identifier and its own audience.

**No aggregate endpoint.** One endpoint exposing every application's tools sounds convenient and
destroys the per-application audience separation that Decision 6 makes the security boundary; tool
names would also collide across applications. The authorization server removes the motive anyway:
**one dynamic registration, one sign-in, N resources**, with each token scoped by the `resource`
parameter to the application it is for.

Metadata placement follows from Decision 6. RFC 9728 inserts the well-known segment between host and
path, so an application's resource metadata is served at
`/.well-known/oauth-protected-resource/orders/_tesseraql/mcp` (the resource URL per Decision 25) —
at the gateway root, not under
the application's prefix. **The gateway needs a rule that reads the inserted path and resolves the
application**, and the document itself should be produced by that application's runtime and relayed,
rather than synthesised by the gateway from the catalogue, so the configuration is not read twice.
The authorization server side needs no insertion at all, because its issuer is the bare origin.

### 19. The development-tool MCP spans the stack, which is the opposite of Decision 18 on purpose

Two MCP surfaces, opposite answers. The contrast is deliberate and worth stating, because it reads
as an inconsistency until the reason is written down.

An **application's** MCP surface is per application with no aggregate (Decision 18) because a token
carries a per-resource audience and that audience is the security boundary; one endpoint spanning
several applications would collapse it.

The **development-tool** MCP has no such boundary to protect. `tesseraql mcp` runs on the
developer's own machine against application homes they already hold on disk, and nothing it does
crosses a trust boundary the filesystem did not already cross. So the argument that forces
separation upstream does not reach it — while the argument that a team building interlocking
applications wants one agent that can see all of them does.

**One server for the stack**, resolving application homes under the install root, with an
application argument on each tool. Three consequences:

- Every development tool's input schema gains the argument, and its description must tell the model
  how to choose. An agent that guesses which application it is editing is worse than one that has
  to be told.
- `McpCommand` builds `McpDevTools(app, readOnly)` today. `readOnly` becomes a property of the
  server rather than of an application — there is no reason to vary it per application, and a
  mixed-mode server would be hard to reason about.
- Narrowing to a single application stays available, because someone working on one should not have
  to see six. That is a scoping flag, not a second mode — [cli-surface.md](cli-surface.md) Decision
  3 makes it `--stack <dir> --app-name <name>`, which narrows what starts without changing anything
  about how it is addressed or configured.

**It does not inherit the authorisation problem Decision 14 records for Studio.** A stack-level
Studio is reached over HTTP by an authenticated subject, so "which applications may this person
edit" is a real question with no current answer. This is a local process run by someone who already
has the files, and conflating the two would invent a permission model where none is needed.

**Shipped 2026-08-17, as written, with one call the decision did not make.** `mcp` resolves the
stack exactly as `dev` does (`--stack`, discovered one level up when omitted; `--app-name`
narrows), every tool and the copilot prompt carry the `application` argument, and read-only is the
server's. The addition: the HTTP transport's bearer check used to read one application's
`tesseraql.security.jwt`, and a stack has several. The members must agree — the server has one
gate, and one that verified each request against whichever member it happened to pick would accept
a token another member rejects — so disagreeing JWT settings refuse the HTTP transport with the
fix named, the same declared-when-divergence-fails-silently shape as the framework-datasource
guard. Stdio is unaffected; it inherits the launching process's trust.

### 20. Token acquisition needs a path a person can follow

`POST /_tesseraql/token` is guarded like the sign-out routes and requires the session's CSRF token,
which is correct — it converts a cookie into a credential that outlives it. But the CSRF token
reaches only pages, as `<meta name="csrf-token">`, so the only route available to a human today is
reading a cookie and a meta tag out of browser developer tools.

Two additions, both small:

- **A console page that issues a token** and offers it for copying. On an authenticated page the
  CSRF token is already present; this is one page.
- **`tesseraql token --url <app> --login <id>`**, which signs in and exchanges in one step. This is
  currently impossible to build: `LoginRouteBuilder` answers a non-browser `POST
  /_tesseraql/login` with `{"ok":true,"loginId":"…"}` and a cookie, and **never returns the CSRF
  token**, so a command-line client can authenticate and then cannot proceed. Returning it in that
  response grants no new capability — the same value already reaches any authenticated browser
  through the meta tag — and leaves Decision 2 of `session-token-exchange.md` intact, since a
  hostile page still cannot read a cross-origin response body.

These serve developers and CI. They do not serve the case in Decision 2; nothing short of OAuth
does.

**Shipped 2026-08-16, with one addition this decision did not name.** The console page and the
endpoint are two faces of the same act, and a page that assembled its own claims would have drifted
from the endpoint the first time a claim was added to one of them. So the signing moved into one
place both call, and the page reaches it through an ordinary service provider bound to the
**ambient** principal — the curated map the request binder seeds from the authenticated exchange
(`ambient-params.md`). That detail is the security property: a route that wired `subject: 'admin'`
would be writing a parameter the provider never reads, so the provider cannot be asked for somebody
else's token.

The page also has to answer when issuing is off, which is the default. It says so and names
`tesseraql.security.token.enabled` rather than raising anything: an application that does not issue
tokens replying "I do not issue tokens" is a correct answer, and a new error code for it would have
reached the operator as a 500 with nothing actionable in it. That is the shape
`ops-console-coverage.md` Decision 1 already took for the audit page and its
`tesseraql.audit.routes.enabled` — a bundled app cannot mount a page conditionally on the host's
configuration, so the page mounts and the provider tells the truth about the flag.

### 21. Framework identification is accepted, explicitly

`threat-model.md` treats information disclosure as enumeration, field exposure, secrets and error
internals. Framework identification is absent, and therefore accepted implicitly. It should be
accepted **explicitly**, because an unstated acceptance reads later as an omission.

The acceptance is sound. The name leaks through channels far stronger than a path prefix: `TQL-`
error codes in responses, which are a deliberate feature; the `tesseraql_sid` cookie name; the login
page's markup and hc asset paths; and, once Decision 6 ships, OAuth metadata that the specification
requires to be public. Renaming the prefix buys obscurity and pays in clarity.

What actually matters is already right: **the version is not disclosed over HTTP** — it appears in
logs and in Studio's internal model, and no `X-Powered-By`-equivalent header was found — and version
is the fingerprint that maps to advisories.

The strongest argument against obscuring the prefix is that **it is a control surface for the
defender**. `location /_tesseraql/studio { allow 10.0.0.0/8; deny all; }` is writable precisely
because the prefix is stable and distinctive; scattering or obfuscating framework surfaces costs the
operator that line and costs an attacker nothing. Making the prefix configurable is rejected for the
same reason, plus two: MCP requires the `resource` identifier to match what the user typed
character for character, and every document, test and example depends on the value.

Attack surface is reduced by **removing surfaces, not renaming them** — `tesseraql.mcp.enabled:
false`, `tesseraql.apps.<name>.enabled` for individual framework applications, and ingress rules by
prefix.

### 22. A stack declares its own settings in a file beside its applications

Decision 16 named three settings that belong to the host and stopped short of building them for one
reason: **a host has nowhere to read its own settings from.** [cli-surface.md](cli-surface.md)
reached the same wall from the other side over the gateway's port, and deferred the answer to here.

**The answer is `tesseraql-stack.yml`, in the directory `--stack` names.** The name carries the
`tesseraql-` prefix by review decision, for the same two reasons twice over: a bare `stack.yml` in
a repository root reads as Docker's (`docker stack deploy` conventions named that file first), and
the prefix makes the file's owner legible in a directory listing the way `docker-compose.yml`
does — a stack directory is otherwise just a folder of folders. It is loaded through the
configuration loader applications already use, so `${secret.…}` and `${ENV_VAR:default}` resolve in
it exactly as they do in `config/tesseraql.yml` — open question 3 already confirmed that
`SecretResolvers.discover()` is process-scoped and reachable at host scope.

```yaml
# work/tesseraql-stack.yml — the same file the deployment ships and the developer runs against
framework:
  datasource:
    jdbcUrl: jdbc:postgresql://${DB_HOST:localhost}:5432/stack
    username: ${secret.env.SUITE_DB_USER}
    password: ${secret.env.SUITE_DB_PASSWORD}
externalOrigin: https://apps.example.com
security:
  jwt:
    algorithm: RS256
    issuer: https://apps.example.com/_tesseraql/oauth
    jwksUri: https://apps.example.com/_tesseraql/oauth/jwks
```

**The file is always `<dir>/tesseraql-stack.yml`, and nothing names it separately.** A draft of this decision
added a `--stack-config <file>` option, because the running commands then also took `--app` and an
application's own tree is nowhere to put a stack's settings. [cli-surface.md](cli-surface.md)
Decision 1 has since removed `--app` from those commands — for four reasons of which this was one —
so the directory `--stack` names is the only place the file can be, and a second source for it
would be a second answer to a question that has one.

**Every stack has a directory, so every stack can hold this file** — which is not a coincidence but
the reason [cli-surface.md](cli-surface.md) Decision 2 refuses to treat an application home as a
stack. A draft of that decision accepted one, to save a single-application repository a directory;
a stack file could not live there, since it would ship inside the application's package, so that
shape could hold **no stack settings at all**. The reply drafted at the time — that a stack of one
has no cross-application divergence to prevent, so it needs none — applied **half** of this
decision's rule. The rule has two limbs, and the external origin is the first one: an application
cannot know its own external origin however few of them there are, and Decision 18 needs it. The
shape was removed rather than patched, and narrowing to one application became
`--stack <dir> --app-name <name>`, which names the stack directory and therefore reads this file.

#### What goes in the file, and what stays on a flag

The rule is decision 16's own, applied to the operator's surface: **a setting belongs in the file
when divergence — between applications, or between development and production — fails silently.**

| Declared in `tesseraql-stack.yml` | Why it cannot be a flag |
| --- | --- |
| `framework.datasource` | Divergence presents as "signing in does not carry", which reads as a framework defect |
| `externalOrigin` | Decision 6 requires MCP's `resource` to match what the user typed character for character |
| `security.jwt.algorithm` / `issuer` / `jwksUri` | One authorization server means one issuer and one key set |

| Stays a flag | Why it does not need declaring |
| --- | --- |
| `--port` | A wrong value fails at bind, naming the port |
| `--http2` | A wrong value fails at the handshake |
| `--trusted-proxies` | It describes *this* deployment's topology, not the stack's |

That closes cli-surface.md's open note with a reason rather than a deferral: **the gateway's port
stays flag-only.** It was never the silent-divergence case that motivated the file.

#### The file is optional, and the alternative to it is agreement rather than silence

A stack of one needs nothing declared, and a development workspace should not have to carry a file
to run. So `tesseraql-stack.yml` may be absent — but "absent" must not restore the failure mode the file
exists to remove.

**When the stack supplies no framework datasource — no file, or a file that does not declare
one — and more than one application runs, the host checks that the applications agree.** It
resolves each application's framework datasource coordinate (the `tesseraql.framework.datasource`
name, then that entry's `jdbcUrl` and `username`, after placeholder resolution) and refuses to start
when they differ, naming each application and its coordinate — **TQL-APP-4211**. (Keyed on what
the file *supplies* rather than its existence, so the marker file `new` generates cannot silence
the check.) The gateway
already loads every application's manifest, for ingress header stripping, so this costs a
comparison rather than a pass over the tree.

The comparison is on the resolved strings, exactly. That will refuse a stack whose applications
name the same database as `localhost` and `127.0.0.1`, which is a false refusal — and it is the
right way round: **a false refusal is loud and one edit from fixed; a false pass is a stack where
signing in silently does not carry.**

#### An application's framework datasource is a name; a stack's is a coordinate

`tesseraql.framework.datasource` names an entry in the application's own `tesseraql.datasources`.
The stack declares a connection instead, and the host builds one pool from it and hands it to every
runtime through `HostContext` — the name indirection is not involved, so no reserved datasource name
has to be invented and no application's registry gains an entry it did not declare.

An application that **explicitly** declares `tesseraql.framework.datasource` while the stack
supplies a coordinate is refused — **TQL-APP-4212**. It asked for framework state on a particular
pool and the host is replacing that pool; ignoring the request would be the silent divergence this
decision exists to remove. The default (`main`, unstated) is not a request, so the host simply wins.

#### Two authors, two moments — and the development stack's file is generated as a marker

**The team writes it, checked in beside the applications**, with `${DB_HOST:localhost}` and
`${secret.env.…}` for anything that varies by environment. **The operator writes it on an install
root, by hand, once, before the first `host`** — `tesseraql install` puts an application and a
catalogue entry there and nothing else, and it should not start inventing a stack's settings from
one application's package.

**Amended (user decision, 2026-08-17): `tesseraql new` generates the file.** This section first
said nothing generates it, quoting [cli-surface.md](cli-surface.md) Decision 8's reason — no
required content, so a generated file would be entirely commented out. That reasoning stands for
the settings and falls for the marker: Decision 9's discovery needs an affirmative sign that a
directory *is* a stack (the parent of an application home always "holds applications" — this one —
so shape alone cannot say), and a marker needs no content to mark. So `new` writes an
all-guidance-comments `tesseraql-stack.yml` beside the application it creates, the way `cargo`'s
`[workspace]` marks a workspace. The **operator/install path is unchanged**: `install` generates
nothing, and discovery there stays a refusal rather than a blank file — an operator meets
`tesseraql-stack.yml` at the moment it matters, when TQL-APP-4211 refuses to start naming it, or
when the documentation names it for MCP or the authorization server. For that to survive a
generated near-empty file, **TQL-APP-4211 is keyed on what the stack supplies, not on whether a
file exists** — see below.

#### The development loop needs no file, and one measurement says why it nearly did

`dev` should require no stack settings at all, and it almost does. Working through
`--embedded-db` found a collision between two decisions that each read correctly alone.

[cli-surface.md](cli-surface.md) Decision 4b tells applications to isolate themselves inside the
shared embedded database with `currentSchema` **in their own URL**. With no `tesseraql-stack.yml`, this
decision falls back to each runtime's own `tesseraql.framework.datasource`, which is `main`. So
application A resolves `…?currentSchema=a`, application B resolves `…?currentSchema=b`, the two
coordinates differ, and **TQL-APP-4211 refuses the whole development stack** — over the isolation
the other decision just recommended. Decision 4b even states the rule that is violated: "an
application's `currentSchema` must not reach it."

**So `--embedded-db` supplies the framework datasource itself**, as the embedded server's shared
database with no schema qualifier. It is not derived from any application — which is 4b's actual
prohibition — it is the same coordinate the CLI already knows because it started the server. One
sign-in across the development stack, no file, and the per-application `currentSchema` stays where
it belongs.

**And `dev` can default the external origin, where `host` must not.** `dev` is the gateway, so it
knows its own address and `http://localhost:<port>` is right by construction. A host behind an
ingress cannot know it, and defaulting there would hand an MCP client a `resource` of
`localhost` — the silent misconfiguration Decision 6 requires character-for-character matching to
prevent. So it is **required when something reads it** — MCP resource metadata, the authorization
server's issuer — and absent until then, rather than demanded at boot from every deployment that
will never use either.

**"No file needed" counted exactly, because review asked for the count.** The beginner paths — one
application, or `--embedded-db` — need nothing, ever. Two edges legitimately want the file, and
both are the design working rather than failing: **several applications against real databases**
whose framework coordinates differ meet TQL-APP-4211, and the refusal's remedy *is* a development
`tesseraql-stack.yml` — which is why Decision 23's multi-team model already expects team repositories to
carry one; and **a development gateway addressed by anything other than `localhost`** (a colleague
across the LAN, a forwarded container port) needs the origin declared, because the default is then
the wrong string for Decision 6's character-for-character rule. Neither is silent: the first
refuses naming the fix, the second only matters once MCP or the authorization server is in play.

**And the second edge needs no new flag, asked directly in review for the single-application
case.** A lone application developing against MCP or the authorization server declares the origin
in `tesseraql-stack.yml` — and the place for that file already exists, because the layout `new`
creates is already a stack directory holding the application:

```
myrepo/
  tesseraql-stack.yml     # externalOrigin, for the machine your MCP client types
  orders/
```

Decision 9's one-level discovery finds the file from inside `orders/` as well as beside it, so
`cd orders && tesseraql dev` reads it with nothing named. A repository whose *root* is the
application home has nowhere to put the file — that is the one restructure this design asks for,
one `git mv` into the layout every stack has, and it is the same answer Decision 2 gives for every
other stack-level need. **A flag naming the file (`--stack-config`) stays deleted**: it was removed
because a second source for a value the stack directory already answers is a second answer to one
question, and this case does not revive the need — it is served by the directory the tooling
already builds.

**Also rejected, proposed in the same review: falling back to a `tesseraql-stack.yml` *inside* the
application home when the parent has none.** It would save the root-is-an-app-home repository its
one `git mv`, and it opens a production path that is silent in exactly the way this document
exists to close: `host --stack` hosts source trees, so an install whose operator has not yet
written the stack file, holding an application whose tree still carries a developer's — would read
**a development machine's origin, issuer and framework datasource as production's stack settings,
saying nothing**. TQL-APP-4211 made absence a check instead of a silence; a fallback reintroduces
the silence one directory down. It also breaks three standing rules at once: an application's
files do not name their deployment, the file's *location* is the environment (a file inside the
application travels to every environment), and a parent file would silently shadow the inner one —
the shape the `--stack ./work/orders` refusal exists to prevent, inverted. What the pinch actually
earns is a better refusal: when `dev` needs the origin and no stack directory exists, the message
prints the restructure — the same refusal-teaches pattern as every other.

**And one rule intersection is scoped here so it never has to be discovered:** TQL-APP-4212 —
an application *explicitly* declaring `tesseraql.framework.datasource` while the stack supplies a
coordinate is refused — applies to a **`tesseraql-stack.yml` supply only**, not to `--embedded-db`'s. The
embedded flag is the developer explicitly saying *replace my databases*: it already overrides the
application's declared `main`, and refusing the framework declaration it also replaces would make
the one flag that means "override everything" the one place an override is refused.

**The `security.jwt` triple follows the same split, asked directly in review.** Today it does not
arise: bearer authentication is each application's own HS256 configuration, minted by
`tesseraql token --app` and the token endpoint, and nothing in this document changes that before
slice 5. Once the authorization server exists, all three keys have development defaults derivable
from what `dev` already knows — the issuer *is* the development external origin above, `jwksUri`
derives from the issuer, and the algorithm is the server's design value — leaving only the signing
keypair, which follows the `--embedded-db` pattern exactly: infrastructure a deployment declares
explicitly, generated by the CLI into `work/` for development and persisted there so issued tokens
survive a restart. The triple sits in `tesseraql-stack.yml` because of constraints that bind **production
only** — an issuer external clients must match character for character, a key set that must be
stable across nodes and restarts — and neither constraint reaches a single-machine loop. So the
rule stays whole: **the development loop needs no stack file**, for these keys too.

#### Resolved: `--env` does not apply, because the file's location already is the environment

Applications resolve `config/env/<profile>.yml` through `--env`, and a first draft left open whether
the stack file should do the same. Walking the operator's journey answered it: **`tesseraql-stack.yml` exists
per deployment directory.** The team's repository holds development's; the staging install root
holds staging's; production holds production's. A profile axis inside the file would be a second way
of saying what the file's location already says — and one flag selecting a profile for the
applications while a *different* mechanism selects the stack's values is a smaller surprise than two
mechanisms selecting the same thing.

So `tesseraql-stack.yml` has no profiles and `--env` does not touch it. Environment variation inside one file
is what `${DB_HOST:localhost}` placeholders are for; a team keeping several environments' files in
one repository keeps them under distinct names and places the right one at deploy time, which is the
same act as deploying anything else.

#### The repository boundary follows the stack, not the application

Guidance rather than a decision, recorded because the file's location makes it unavoidable.

**The source repository should hold a stack.** Decision 12 says a team must develop against the
topology it deploys; a layout where nobody can check out *the stack* cannot satisfy it. Applications
in separate repositories need submodules or a meta-repository before `dev --stack` has anything to
point at, and `tesseraql-stack.yml` — which belongs to the whole — has no home.

**Independent release is not a reason to split**, because it is already available without splitting:
`.tqlapp` packages and `AppCatalog` exist precisely so applications can be installed at different
versions into one install root. **The distributable unit is an application; the source unit is a
stack.**

**When to split is the same question as when to stop being one stack:** applications that no longer
share an origin and a sign-in are not a stack, and their repositories should part at the same
boundary their deployment does.

#### Rejected

**Flags alone.** The list reaches eight options, a database password lands in shell history and
process listings, and the values that must be identical between development and production live
nowhere a repository can hold them — which is exactly the case the stated priority names first: a
single team building interlocking applications, developing locally in the shape they deploy.

**A file with flag overrides.** cli-surface.md rejected the same shape for `--port` and for
`server.port`, both times because one meaning acquired two sources. A reader of a running deployment
would have to check both, and the interesting failures are the ones where the file says what
everybody read and the flag says what actually ran.

**A key inside each application.** This is decision 16's original rejection, unchanged:
`CookiePath`'s documentation already records why an operator setting a stack-wide value per
application is a defect generator rather than a flexibility.

#### What it owes

A JSON schema sidecar for the documentation portal, an entry in the lint registry so a malformed or
misspelled `tesseraql-stack.yml` is a refusal rather than a shrug, and a `hosting.md` section. **The word
already means something else in this codebase** — `TestSuite` is a set of route tests inside an
application — and `--stack` (cli-surface.md decision 1) already committed the deployment sense of
it. The scopes do not overlap in any path, so the collision is accepted and named rather than
renamed around.

### 23. `tesseraql-stack.yml` declares intent and the catalogue records inventory, because applications deploy one at a time

Asked as a user question and answered with two requirements: teams develop their applications
independently — five in one team, six in another — while **users see one sign-in**, and **deployment
happens per application**. Those two requirements split the stack into two lifecycles, and the files
follow the lifecycles.

**The stack's topology changes rarely and is owned by the deployment**: the framework datasource,
the external origin, the issuer (Decision 22) — and the applications' addresses. **Applications
arrive and upgrade continuously, each on its own schedule, owned by their teams**: name, version,
install path, entitlements. So:

- **`tesseraql-stack.yml` is the intent file**, written by people: the framework datasource, the external
  origin, the issuer (Decision 22), and the root pointer (Decision 24).
- **`catalog.json` is the ledger**, written by install tooling and never edited by hand.

**And the address is in neither file, because it stopped being declarable at all.** A first draft of
this decision moved `basePath` from the catalogue into a `tesseraql-stack.yml` `applications:` section. Review
then removed its cases one by one: the root went to Decision 24's redirect, root *ownership* was
dropped rather than guarded (Decision 25), and what remained of a declarable address was the vanity
rename — which this decision itself rejects, because a renamed address breaks every neighbour's
links. So **an application's address is derived, always: `/<name>`**. `InstalledApp.basePath` stays
as the in-memory carrier with exactly one producer, and the `basePath` field leaves `catalog.json`,
revising #834 — whose motivating case, the one-application stack serving at the root, is served by
the redirect instead. Installing a new version of an application cannot change where it answers,
because *nothing* can.

**The multi-team model falls out rather than being designed.** A team's repository holds a
*development stack* — its own applications, its own `tesseraql-stack.yml`, which under Decision 22's
file-is-the-environment rule is development's file. The deployed stack composes both teams'
applications under one operator-owned `tesseraql-stack.yml`. No team reads the other's stack file, and no
coordination meeting assigns addresses, because **the `/<name>` default makes the name the
inter-team contract** — which is what the migration-history work made names required and unique
*for*, and Decision 25 makes literal. Cross-application links are absolute `/<name>` paths, so address overrides break
neighbours' links; overrides are for the deployment's root choice and little else — and Decision
24's `root.redirect` serves that choice *without* an override, keeping the canonical address, so
most stacks override nothing.

**The walk found a defect.** If names are the namespace, a collision must be refused loudly, and
`AppCatalog.register` is `apps.put(app.id(), app)` — a second team installing an application under a
name already taken **silently replaces** the first team's entry, indistinguishable from an upgrade.
A guard is owed with the implementation; its minimum shape is that `install` says what it replaced,
and its open question is whether same-name-different-application can be detected at all, or whether
name governance is documented as the teams' responsibility the way service names are.

### 24. The root always redirects, and the portal it defaults to lives inside the fence

Stated as a requirement in review: the root of a stack should lead to **a real application
portal**, not a routing gap. Today the relay answers the unclaimed origin root with
`TQL-APP-4040`, so the first URL anyone types into a fresh deployment — or a fresh `dev` — is a
404.

**The portal is a framework surface at `/_tesseraql/portal`** (slashless like
`/_tesseraql/login` — routes derive their paths from the `web/` layout, and the earlier drafts'
trailing slash was cosmetic), and `/` is a **307 redirect to it by default**. A first draft served the portal *at* the root, and review caught what that was: the
only framework surface outside the `_tesseraql/` fence — the single exception to Decision 17's one
rule. Placed inside it, the rule has no exceptions, an ingress line fencing `/_tesseraql/` covers
the portal with everything else, and the root stops being a place where content lives: **`/` does
exactly one thing — redirect — and configuration chooses only the target.**

- **The portal**: anonymous → the stack's sign-in, `next=/_tesseraql/portal` — a real address, not
  `/`. Signed in → the applications this principal may reach, filtered, as links. One screen
  answers "what is here and who am I here" — the intranet home page, which for the
  internal-business-application deployments this architecture serves is a product surface rather
  than a nicety.
- **The configured target**, suggested in the same review. `tesseraql-stack.yml` declares it by *name*, not
  by URL:

  ```yaml
  root:
    redirect: orders     # /  →  /orders
  ```

  For the one-main-application deployment this keeps the application's canonical `/orders`
  address — the name contract Decision 23 rests on — while the bare origin still lands users
  somewhere useful. Naming an application the
  stack does not hold is refused at start, like every other disagreement. The redirect is
  **temporary (307), deliberately**: a permanent redirect is cached by browsers past the
  configuration change that retires it, which would turn an operator's edit into a support ticket.
- **No precedence, because there is only one mechanism.** `/` 307s to `root.redirect`'s
  application when `tesseraql-stack.yml` names one, and to `/_tesseraql/portal` when it does not — one
  behaviour with a default target, not branches to order. Two earlier drafts each had a branch more:
  one served the portal at the root as content, and one kept an application *owning* the root via
  `basePath: /` for the public site whose URLs must not carry a prefix. Review removed both — the
  first for breaking Decision 17's fence, the second (Decision 25) for sitting outside the persona
  while already costing a guard. Restoring ownership later is additive.
- **Development and production get the same screen** (Decision 12's parity). This costs the
  development loop nothing new: the five-minute demo already begins with "First login" against a
  seeded identity store, so sign-in-first at `/` is the flow developers already have.
- It is a stack-level framework surface in Decision 14's sense and ships with slice 4's identity
  surfaces, which is where its two ingredients — the sign-in redirect and the entitlement check —
  already live.

**Implementation design: [root-portal.md](root-portal.md)** (2026-08-18) — the stack surface
runtime, the relay's origin fence, the slices, guards and tests, and the open questions that gate
them.

**Status 2026-08-18: shipped, in the design's three slices.** The stack surface runtime and the
origin fence (with TQL-YAML-1405's `assets` reservation); the portal page and its
tenant-filtered `portal.apps.list` provider; the root's 307 with `root.redirect` and
**TQL-APP-4215**. All four of the design's open questions closed on their recommendations.

### 25. An application's address is its name — `/orders`, not `/apps/orders`

Asked in review: framework surfaces are already fenced under `/_tesseraql/`, so why do applications
carry an `/apps/` wrapper? Measured, the wrapper defends nothing the name grammar does not already
defend — and the grammar is the finding.

**What separates the root namespace is the character set, not the prefix.** Application names are
`[a-z][a-z0-9-]{0,63}`: no leading underscore, so `/_tesseraql/*` is unreachable by any name; no
dots, so `/.well-known/*`, `/favicon.ico` and `/robots.txt` are unreachable; no slashes. The
`/apps/` prefix was a second fence around a namespace the grammar already fences. And since #834 the
relay routes by comparing declared prefixes — nothing parses an id out of a constant — so `/orders`
routes exactly as `/apps/orders` does, segment-boundary matching included.

The user-seat gain is the point: these are internal business applications whose URLs appear in
mails, bookmarks and browser bars daily, and `/orders/invoice/123` is the address a person would
have guessed. It also completes Decision 23's contract — *an application's address is its name*,
now literally.

**The condition this stands on, found by measuring:** nothing on the manifest path constrains the
name's characters. The scaffolder enforces `[a-z][a-z0-9-]{0,63}`, but a hand-written manifest can
name an application `_tesseraql` today. That was already wrong — a name with a slash breaks the
address, the history table and the `ops.app.<name>` grant — and this decision makes the character
rule load-bearing for the URL space. **TQL-YAML-1405** adds it to `ApplicationNameRules` beside
1404's presence check, refusing at lint and at boot. **The rule is segment safety, not the
scaffolder's ASCII pattern**: no leading underscore or dot, no slash, one valid path segment. The
history-key work measured names in *UTF-8 bytes* precisely because names are not confined to
ASCII — a rule that outlawed the names that guard exists to measure would be revising a shipped
decision by accident. The scaffolder's stricter pattern remains what `new` generates.

**What is honestly given up, from Decision 17's own grounds** (its "two root names" note is amended
to point here): the framework's claim on the origin root goes from two fixed names to
`/_tesseraql/`, `/assets/` — the stack surface runtime's asset bundle, the same per-scope claim
every application scope already carries, with `assets` reserved as a name by TQL-YAML-1405 so no
member can shadow it ([root-portal.md](root-portal.md)) — plus *every application name*, so the
root is no longer "left to the operator". The
operator case Decision 17 imagined — `/health` for a load balancer — is already served by
`/_tesseraql/health/live`, which is what the deployment image's own healthcheck calls; and an edge
that wants to fence application traffic wholesale writes "everything except `/_tesseraql/`" instead
of `/apps/`. One rule either way.

**One collision would have widened, and review removed its precondition instead of guarding it.**
An application owning the root would be shadowed by every sibling's name where under `/apps/` it was
shadowed by one unlikely path. A first draft answered with a start-time check; the review question —
*why allow root ownership at all?* — was better. Two mechanisms defending a shape is the sign the
shape is wrong, root ownership's remaining case sat outside the persona, and Decision 24's redirect
serves the deployment's root choice without it. So **`basePath: /` is not accepted**: the origin
root always redirects — to the portal by default, to a named application by configuration — and is
never an application itself, so the shadow guard never needs to exist.

Rollout note: `/apps/<name>` was the shipped default from #834; the derived `/<name>` address
shipped with the 0c catch-up PR, which also took `basePath` out of `catalog.json` (a catalogue
still declaring one is refused) and moved `hosting.md`/`base-path.md` with it.

### 26. Cross-application configuration: values share through the environment, declarations do not share at all

Asked in review: eleven applications, one SMTP relay, a business database some of them share —
where does the common configuration live?

**Not in `tesseraql-stack.yml`.** Decision 16 drew this line when it drew the other one: "this is not a call
to hoist configuration generally." The stack file carries the settings that pass its two-limb rule
and nothing else, or it becomes the thing it replaced — one file whose edits reach applications that
did not ask.

**The sharing plane for *values* already exists, and it is stack-scoped by construction.**
Measured: `AppConfig` resolves `${key}` / `${key:default}` against **the environment first**, then
the configuration tree, and its environment source is `System::getenv` — and one stack is one
process, so one environment reaches every runtime in it. `SecretResolvers` is process-wide the same
way. So the pattern is:

- every application that uses the relay writes `host: ${MAIL_HOST:localhost}`;
- the deployment sets `MAIL_HOST` **once** — in the service unit, the container, the shell;
- development sets nothing, because the default rides in the placeholder.

A value written once per deployment reaches every application that names it, which is the sharing
the question asks for — with no new file, no new precedence layer, and no mechanism to document.

**The *declarations* stay per application, and that is a feature with three names on it.** An
application declaring its own channels, datasources and connectors is what keeps it **lintable
alone** (`AppLinter.lint(app)` has no stack parameter), **packageable** (the `.tqlapp` is the whole
application), and **deployable one at a time** (Decision 23's lifecycle). Three of the seven
examples declare a `notifications:` block; that repetition is each application stating what it
needs, with the values shared through the environment underneath.

**Rejected: a `shared:` or `defaults:` section in `tesseraql-stack.yml`, merged beneath every application's
tree.** It reads like a convenience and it is the silent-divergence shape wearing one: an
application's behaviour would no longer be determinable from its own files, so lint, packaging and
review would all need the stack context, and the same `.tqlapp` would behave differently in two
stacks with nothing in either application saying so. It would also add a fifth precedence layer to
`application.yml` < `tesseraql.yml` < profile < `overlay.yml`, and every position it could take
surprises someone.

**The growth path is per-key, through Decision 16's rule.** The issuer and JWKS earned their place;
IdP brokering will arrive as a named `tesseraql-stack.yml` key when slices 4 and 5 land, with its reason
attached. What the environment cannot carry is named honestly: a *structured* shared block — a
whole channel definition — does not ride in one flat string variable. If a real case appears where
structural sharing has silent-divergence risk, it earns a named key the same way, not a merge.

### 27. There is nothing above a stack

Asked in review: does "multiple stacks" mean a supported composition, or just several independent
processes whose sign-ins are separate? **The latter, and the absence of the former is a decision,
not a gap.**

A stack is one process, one origin, one sign-in, one framework datasource, one portal, one
authorization server. Running two stacks is running two of everything, and the separation is
complete — which is not a side effect but the definition. Decision 12's replacement for independent
hosting already said it: *an application that must not share a session with its neighbours gets its
own stack.* **A stack is the name for how far one sign-in reaches.** The commonest plural is not
even organisational: staging and production are two stacks of the same applications, which is what
Decision 22's file-is-the-environment rule is for.

**TesseraQL builds no mechanism above the stack** — no federation, no cross-stack routing, no
cross-stack login, no stack of stacks. The layer above a stack is which origins exist and which
stack answers each, and that belongs to DNS and the operator's proxy, which already do it well.

Two consequences are worth their own lines:

- **People span stacks through a brokered IdP, and only that way** (Decision 7). Two stacks
  brokering to the same corporate provider give users one authentication and an SSO experience,
  while each stack keeps its own sessions, roles and entitlements. The tempting alternative —
  pointing stack B at stack A's authorization server as its identity provider — is outside
  Decision 4's boundary ("for its own users, and only for those") and makes one stack load-bearing
  for another's login. It is not offered.
- **A framework datasource belongs to exactly one stack.** Pointing two stacks at one shares the
  session table and the identity store across origins. Browser cookies keep ordinary users apart,
  so it *appears* to work — but a session identifier obtained from stack A then resolves as valid
  when presented to stack B, an attack that separate stores refuse outright, and the shared
  identity store is an undesigned back door to multi-origin identity, which is what brokering is
  for. The `security` migration hoist is where a guard could live if this needs more than
  documentation; until then it is the operator rule the sentence above states.

### 28. A module belongs to the application that declared it, and the stack makes that literal

Asked in review: how are per-application modules handled? Measured, the declaration side is already
right and the effect side contradicts it — and a stack turns the contradiction into a defect.

**Declared per application, correctly.** `tesseraql.modules` in the application's own
configuration, Maven coordinates locked by `modules.lock`, resolved into that application's
`work/modules`. The module set is part of the application the way its datasources are: declared in
its tree, packaged with its identity, lint-checkable alone (Decision 26's three names apply
unchanged).

**Wired per process, wrongly for a stack.** `serve` composes the module jars onto the *thread
context classloader* and calls `ExpressionFunctions.install(loader)` — whose registry is a single
`static volatile` map and whose own javadoc says *"replacing any previous installation."* One
application, one process: harmless. N runtimes in one host process: **the last application's
install replaces every neighbour's custom functions.** A route referencing its own function loses
it — or silently binds a neighbour's function of the same name with different semantics, which is
the worse outcome because it answers instead of failing. `DriverManager` has the milder version:
drivers are additive through the shim, but first-wins per URL, so two applications declaring the
same driver at different versions get whichever loaded first. And `host` wires none of this today,
so modules in a stack are not degraded — they are absent (the slice 3 gap, now with its mechanism
named).

**The decision is the scope: module visibility equals runtime scope.** Each hosted runtime gets its
own classloader over its own application's `work/modules`, and the function registry becomes
per-runtime state — bound where the tracer and lanes already bind, not process-global. An
application's behaviour is then a function of its own declarations, in a stack exactly as alone.

**Rejected: one union classloader over every application's modules.** Cheaper to build and it is
the silent-divergence shape a third time: an application's routes change meaning because a
*neighbour* declared a module, nothing in the application says so, and name collisions across teams
resolve by load order. Decision 23 made the name the inter-team contract; a union loader would make
function names an accidental one.

**Implementation obstacles, then measured smaller than they read.** `ExpressionFunctions` is the
static-global shape this decision retires, and the measurement is good news: core reads it in
exactly **two places** — evaluation (`Expr.java:210`) and parse-time arity
(`ExpressionParser.java:191`) — six callsites in total, so carrying the registry per context is a
small surgery, not a deep one. **Drivers resolve at the pool, which is the real seam:**
`DataSources` sets only `jdbcUrl`/`username`/`password`, so Hikari falls back to `DriverManager` —
JVM-global, first-wins per URL. Each application's pool binding its driver from its own module
loader removes `DriverManager` from the load-bearing path and lets two applications carry the same
driver at different versions. The single-application CLI commands change nothing: one invocation is
one application. And `mcp --stack` (Decision 19) evaluates against several applications from one
process, so the development-tool MCP needs the same per-application context the host needs — it
cannot ride one TCCL either.

**Deployment note:** module resolution reaches Maven repositories, so it happens at **install
time** — `tesseraql install` resolves the declared set into `work/modules` under the lock — and a
production `host` boots offline from what install resolved. `dev` resolves at start, which is the
loop where the network is already assumed. The explicit `--modules <dir>` stays what it is today, a
development override composed onto **every** runtime in the stack, and is documented as exactly
that.

**Implementation design: [module-scope.md](module-scope.md)** (2026-08-18) — functions bind at
parse and the registry becomes a value, the runtime owns its module loader, drivers bind at the
pool, the slices, guards and tests, and the open questions that gate them.

**Status 2026-08-18: shipped, in the design's four slices** (2 and 3 merged as one — the design
doc records why). The function set is a value captured at parse; each hosted runtime owns and
closes its module loader; `host` refuses declared-but-unresolved modules (**TQL-APP-4216**) and
a lock-diverged `work/modules` (**TQL-APP-4217**); pools bind module-defined drivers; the MCP
dev tools answer per application. All four open questions closed on their recommendations.

### 29. Deploying an application replaces its runtime, not the stack

Stated as a requirement in review: deploying one application must not affect the others. Measured
against the code, the requirement's negation is the current behaviour: **`MultiAppHost` has no
replace operation** — start, lookup, canary accessors, close — so shipping a new version of one
application today means restarting the host, which restarts every application in the stack.

**The building blocks already exist**, which is why this is a decision and not a campaign:

- **Install is already side-by-side.** Versions land in `<name>/<version>`, so the running
  runtime's files are never touched by installing the next version — the delete-and-replace path
  only fires on a same-version reinstall, and `runtime-footprint.md` already owes it the
  install-beside shape for Windows' sake. The impact boundary on disk is already the application.
- **The canary machinery is a replace with a ramp.** The host already runs two runtimes for one
  application behind one address and splits traffic by weight per request — which means switching
  targets is already a live operation. Replace is: start the new version's runtime (its own
  classloader, its own pools, its own function registry — Decision 28 is a **prerequisite**, since
  without per-runtime module state, reloading one application clobbers its neighbours' functions at
  runtime), health-check it, move the weight to it, drain and stop the old.
- **Sign-in survives the replace without any work.** Sessions live in the framework datasource's
  JDBC store, not in the runtime — the same property that makes a multi-node deployment share
  sessions makes an in-place replace keep them.

**What a replace is refused over:** a new version whose framework-schema expectation is ahead of
what the host migrated — the validate-don't-migrate guard (Decision 16) is the check, and it turns
"deploy one application" into "first migrate the stack, then deploy" **loudly** instead of letting
one application's upgrade quietly re-migrate a schema every neighbour is standing on.

**What stays stack-scoped on purpose**, so the requirement is stated with its boundary: the
gateway, the framework schema, `tesseraql-stack.yml`'s values, and the process itself. Replacing those *is*
deploying the stack, and pretending otherwise would be the independent-hosting mistake at a
different layer.

**Open, for the implementation slice:** how a replace is triggered — an ops-console action, an
`install` that notifies a running host, or a catalogue watch — and the drain policy for in-flight
requests on the retiring runtime. The mechanism candidates all sit behind the same host operation,
so the trigger can be chosen last.

**Implementation design: [runtime-replace.md](runtime-replace.md)** (2026-08-18) — replace is
the canary lifecycle with the ramp collapsed, the trigger is the install root's state with the
host reconciling to it, and a `deploy` verb is the operator's pen; the slices, guards and tests,
and the open questions (trigger, drain policy, verb shape, ready probe) each carry a
recommendation there.

**Status 2026-08-18: shipped, in the design's three slices.** The host replaces live —
admission re-runs the boot guards, the swap follows a ready probe, a failed replace is a
no-op — and the stack's own stop drains instead of hard-killing; a running host converges to
the install root's state and reports each outcome in `.upgrade/<name>.status.json`;
`tesseraql deploy` writes the intent (package, `promote`, `rollback`, `weight`, `status`,
`--wait`, `--sha256`), refusing a catalogue-less directory (**TQL-UPGRADE-4092**). All five
open questions closed on their recommendations, the fifth as deferred to the grants work.

## The scope ledger — what an application owns, what the stack owns

Asked in review, after the decisions accumulated: what, in the end, is application-scoped and
what is stack-scoped? The two rules that draw the line are already decided — **a thing belongs
to the application when it is part of the application's declaration** (in its tree, lintable and
packageable alone — Decision 26's three names), and **to the stack when it defines how far one
sign-in reaches** (Decision 27) **or when only the host can know it or divergence fails
silently** (Decision 16, the `tesseraql-stack.yml` admission rule). This section is the ledger
those rules produce, kept here so a new surface gets classified by rule rather than by habit.

**The application owns** — declared in its tree, effective in its runtime:

- Every declaration: routes, schema and migrations (history `tql_schema_history_<name>` on its
  own business datasource), views, jobs, decision tables, MCP tools, tests.
- Its name — the one identity (Decision 23's inter-team contract), from which its address
  `/<name>` is derived, always (Decision 25).
- Its datasources and pools — and with Decision 28, the JDBC drivers those pools bind, from its
  own module loader.
- Its modules, end to end: the `tesseraql.modules` declaration, `modules.lock`, `work/modules`,
  the classloader over them, the expression-function registry, its file codecs and blob-store
  provider, its runtime extensions, its `plugins/` directory.
- Its egress declarations: `http.outbound` and `connectors.poll` allow-lists, webhook verifiers,
  messaging channels — the *permissions* for capabilities the framework ships.
- Its per-runtime operational state: the Camel context, tracer, lanes — and its unit of
  deployment, the runtime a replace swaps (Decision 29).

**The stack owns** — known only at the host, or defining the reach of a sign-in:

- The gateway and everything on the operator's flags: the front `--port`, `--trusted-proxies`,
  `--http2`, and the root's behaviour (`root.redirect`, the portal — Decision 24).
- The framework datasource — one pool, one stack, never shared further (Decisions 22 and 27) —
  and the `security` schema's migration, which the host runs once and every runtime validates.
- Sessions and the identity store, and therefore sign-in itself: the surface runtime at the
  origin with the portal, `auth-ui` and `account` (Decision 24), growing toward IAM Admin and
  the Studio/ops shells (slices 4, 7, 8).
- `tesseraql-stack.yml`'s values — `framework.datasource`, `externalOrigin`, `security.jwt.*`,
  `root.redirect` — and the catalogue: membership, the name registry and its collision refusals.
- The process environment: `${ENV}` values and secret resolvers (Decision 26 — one stack is one
  process, so these are stack-scoped by construction).
- The base classpath: the framework's own jars, the framework datasource's driver, the Camel
  components under `ComponentGuard`'s baseline.
- The process itself. Replacing any of these *is* deploying the stack (Decision 29's boundary).

**Wired to one, effective for the other — the honest middle, named so it is not misread:**

- **Identity extensions** (`tesseraql-oidc`/`-saml`/`-scim`): declared and mounted per
  application, but a login they complete or an account they provision lands in the framework
  datasource — stack-wide effect. Interim shape: declare them on the member that fronts
  sign-in; their destination is the surface runtime (Decision 14's slice-4 remainder and the
  authorization-server work), and the move will need a mechanism for the stack to hand the
  surface runtime modules — deliberately not designed in Decision 28.
- **Framework connector capabilities** (SFTP/FTPS polling, HTTP egress, mail): the capability
  ships on the base classpath and is stack-level by residence; which hosts may be reached and
  which jobs poll is each application's declaration.
- **`--modules <dir>`** on `dev`/`mcp`: a development override composed onto every runtime — the
  one deliberately stack-wide module input, because it overrides rather than declares.
- **Per-application grants in the shared store** (`ops.app.<name>`): the store is the stack's,
  the grant names one application; their exact semantics are open question 4 (slice 7).

## Slices

Ordering is by dependency, not by size.

| # | Slice | Depends on |
| --- | --- | --- |
| 1 | ~~Gateway transparency: streaming request bodies, response bound removed, SSE flush measured, differential test, `hosting.md` division~~ — **shipped 2026-08-16**; the measurement found a dropped-body defect beside the predicted buffering one and moved the relay to `vertx-http-proxy` (Decision 13) | — |
| 2 | ~~Login response returns the CSRF token; `tesseraql token --url`; console issue-token page~~ — **shipped 2026-08-16**; all three as designed, plus the mint extracted so the page and the endpoint cannot drift (Decision 20) | — |
| 3 | Base path becomes catalogue-driven; independent hosting removed; the gateway-less shape removed; host context object carrying framework datasource, external origin and issuer/JWKS, over the `tesseraql-stack.yml` Decision 22 introduces; `security` migration hoisted to the host with runtimes validating; CLI entry point for the stack, including the stack-spanning development-tool MCP (Decision 19) | 1 |
| 4 | Identity surfaces become stack-level: `auth-ui`, `account`, IAM Admin extracted from the runtime module; the root portal (Decision 24) | 3 |
| 5 | Authorization server: candidate decided, endpoints, open DCR, consent per client and resource, refresh rotation with reuse detection, RS256 and JWKS, brokering to an external provider | 4 |
| 6 | MCP resource metadata, the transport gate, and the gateway's well-known routing (`audit-hardening.md` slices 6 and 7) | 5 |
| 7 | Ops console becomes a stack-level shell with a switcher, delegating over HTTP | 3 |
| 8 | Studio becomes a stack-level shell with a switcher, including per-application edit authorisation | 7 |

**Slice 3 carries the largest hidden cost**, and it is not the deletion — but the cost is not where
this note first put it. It said ② has no CLI entry point and that building one is the slice's main
body. `tesseraql host` exists and is registered, and Studio behind the gateway shipped as
`app-isolation-model.md`'s follow-up 5 (#701), verified by `StackModeIntegrationTest` (then `SuiteModeIntegrationTest`, at the then-default `/apps/<id>/`) opening Studio. Two of the four items named were already done.

**The measured gap, 2026-08-16.** What `serve` carries and `host` does not: `--env`, `--log-format`,
`--log-level`, `--watch`, `--modules`, `--embedded-db`, `--embedded-db-port`,
`--embedded-db-version`, and with them `ModulesInstaller`, the `CliModules` classloader,
`ExpressionFunctions.install`, `ModuleDrivers.register` and `EmbeddedPostgresSupport`.

Some of that is cheap. `watchRoutes` is a per-runtime method, so a stack watches by calling it on
each hosted runtime rather than by growing a mechanism.

**The structural half is the real body, and it is one sentence long:** `serve --app <dir>` runs a
source tree being edited, and `host --install-root <dir>` runs installed packages recorded in a
catalogue. Making the stack the only shape means the development loop has to point at source trees
without packaging them first.

**That is decided in [cli-surface.md](cli-surface.md), which slice 3 now depends on.** Its answer,
after the amendments of 2026-08-16: the commands that *run* applications take `--stack` only — a
catalogue or a folder of source trees, both being "the applications here", and always a directory
that *holds* applications rather than one that is one —
`--install-root` is replaced by the word this document already uses, narrowing is
`--stack <dir> --app-name <name>`, and `serve` becomes `dev`. `--app` keeps its own meaning on the
commands that operate on one application, where it never denoted a deployment.

**Slice 8 is a campaign, not a slice.** `StudioService` is roughly 1,878 lines after the refactoring
campaign and couples preview, source editing, apply and reload, the scaffolder, the migration author
and the test runner to a runtime. It is listed here for ordering and should be designed separately,
on the delegation pattern slice 7 establishes.

## Out of scope

- **Accepting two token issuers in parallel** (Decision 7 case C). One application-wide `jwt:`
  block, one algorithm, one key source. Widening it to an issuer-to-key-source map is a separate
  decision that case B largely removes the demand for.
- **Process separation** (Decision 15). Deferred with named triggers, and kept reachable rather
  than assumed away.
- **A stack-level aggregate MCP endpoint** (Decision 18).
- **A general-purpose identity provider.** No SAML brokering as a product, no federation beyond
  Decision 7 case B, no MFA. Each of these returns Keycloak's argument.
- **CIMD.** No client implements it (Decision 3). Watch the 2027-07-28 deprecation horizon.
- **Cross-application aggregate views** beyond the switcher. Metrics already label job runs by
  `job`, `app` and `status`.

## Open questions

Each carries the slice it gates, because an open question with no gate drifts. Two of the six are
**measurements rather than decisions** and should be taken early, since both are cheap and either
could invalidate a design assumption.

1. **Confirm the grant-layer path** — *gates slice 5.* Decision 5 settled the axis and the
   2026-08-16 measurement settles the rest. What remains is confirmation rather than
   investigation: read `AuthorizationCodeGrantHandler` and `AbstractGrantHandler` end to end
   before committing, because a package-level count cannot show how the five
   `Response`/`WebApplicationException` references behave in the paths that matter.
2. ~~**The development-tool MCP in a stack.**~~ **Closed by Decision 19**: one server spanning the
   stack, with an application argument on each tool. Kept in the list rather than deleted, because
   the alternative it rejected — a server per application — is the one a reader is likely to
   propose again.
3. ~~**Whether the secret provider is reachable at host scope.**~~ **Closed 2026-08-16: yes, with
   no change to Decision 16.** `SecretResolvers.discover()` is a static factory with no application
   context, in `tesseraql-yaml`, which the runtime module already depends on. `EnvSecretResolver`
   reads the process environment, and `FileSecretResolver` resolves against a **process-wide**
   directory — `/run/secrets`, overridden by `tesseraql.secrets.dir` or `TESSERAQL_SECRETS_DIR` —
   rather than anything application-relative. A host resolves exactly what a runtime would.
4. **The permission vocabulary after Decision 14** — *gates slice 7.* `ops.app.<name>` shifts
   meaning, and Studio needs per-application edit authorisation that has no equivalent today.
   **Answered in [stack-shells.md](stack-shells.md)** (2026-08-18, design approved; the
   grammar, the ops atoms and the shell shipped with its slice 1;
   reshaped in review into a persona model): marked atoms `tql.<family>.<verb>.<name>`
   over dot-free application names (TQL-YAML-1405 widens; `tql` is the one reserved name,
   the `/_tesseraql/` philosophy applied to permissions) — `tql.app.use` for business
   users, `tql.ops.view`/`tql.ops.run` for operators (the `ops.batch.*` entry pair and the
   `ops.app.<name>` string retire with the mounted-apps premise that justified them),
   `tql.app.deploy` for the deploy surface, `tql.studio.edit` reserved for slice 8,
   `tql.iam.admin.*` store-wide; roles remain the deployment's bundles, and a framework
   surface checks atoms, never roles.
5. ~~**What `TQL-SEC-4146` becomes**~~ once TesseraQL holds a private key. **Closed 2026-08-19 by
   `token-issuance.md` Decision 9: it narrows.** The session-token exchange signs with the
   authorization server's key when the server is enabled, so the refusal keeps firing only when
   issuing is enabled and no key material of either kind exists.
6. ~~**How Codex actually behaves on connection**~~ — **measured 2026-08-19.** Codex registers the
   complete callback (`http://127.0.0.1:<ephemeral port>/callback/<callback_id>` by default,
   `<mcp_oauth_callback_url>/<callback_id>` when configured) and sends the same URI at
   `/authorize`, re-registering on retry with its new port — so strict exact match works, and this
   entry's original expectation is corrected in Decision 3. Without `scopes_supported` Codex
   proceeds and, unconfigured, sends no `scope` at all, confirming Decision 11. Recorded in
   `token-issuance.md` open questions 2 and 3; still unobserved there: the RFC 8707 `resource`
   parameter.
