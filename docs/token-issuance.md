# Token issuance

Status: **designed 2026-08-16; campaign started and every slice shipped 2026-08-19.** This is
the implementation design for the authorization server
[stack-architecture.md](stack-architecture.md) decided to build. That document decided *whether*
and *what for*; this one decided *how* — and the how is now on main: the module and its store,
the signing keys and the JWKS, issuer unification, `/authorize` with consent and the acting-role
face, `/token`, `/register`, the RFC 8414 metadata, the account page, and the MCP resource side
with its transport gate. **The Decision 11 acceptance ran the same
day and passed**: Claude Code, against a live stack whose only identity source was TesseraQL's
own login, walked discovery, dynamic registration, sign-in, the consent screen, code, `/token`
and the gated MCP surface — a person completing the consent in a browser, the one step no
automation could — and the account page listed the live connection. The campaign is done.

The campaign-start revision reconciles the design with what shipped between those two dates — the
stack surface runtime, the stack file's security graft, the per-application role model — and closes
the two open questions that needed no measurement (the error domain, Decision 10; the fate of
`TQL-SEC-4146`, Decision 9). The protocol design of 2026-08-16 is unchanged; what changed is where
the endpoints live and how the rest of the stack comes to trust what they sign.

Same-day review closed the rest. The Codex leg of the connect-and-observe pass was run and
answered open questions 2 and 3 — exact-match redirect validation survives contact, and
`scopes_supported` may stay absent — and open questions 6 through 8 were closed on their
recommendations. The one observation still owed from that setup is whether the measured clients
send RFC 8707 `resource`; the fail-loud authorize placeholder stands until it is taken.

**What it implements.** Decisions 4 through 11 of `stack-architecture.md`: an authorization server
for TesseraQL's own users, colocated with the resource server, signing RS256, issuing refresh
tokens, registering clients dynamically, and requiring consent. It is a stack-level surface at
`/_tesseraql/oauth`, mounted on the stack surface runtime the way Decision 14's other framework
surfaces now are (Decision 8 below).

**What it is not.** Not a general identity provider — no SAML brokering as a product, no federation
beyond Decision 7 case B, no MFA. `authorization-server.md` records why each of those returns
Keycloak's argument, and that document's survey of alternatives stands; only its headline decision
was reversed.

## What shipped since the design, and what each changes here

When this document was written, "hosted the way Decision 14 hosts the other framework surfaces"
was a plan. Three campaigns landed since, and each one either concretizes a sentence here or
hands this design a mechanism it had to assume:

- **The stack surface runtime exists** ([root-portal.md](root-portal.md)): a framework-owned
  runtime at origin scope serves the portal, sign-in and the account surface behind the relay's
  origin fence (`/_tesseraql/*`, `/assets/*`), and that document explicitly reserved
  `/.well-known/*` at the origin for this one. Decision 8 makes the mounting concrete.
- **The stack file's `security:` subtree grafts onto the surface runtime** (the deploy-surface
  slice of the stack shells): `security.jwt.*` and `security.token.enabled` at the origin already
  make the stack an HS256 issuer for its own surfaces — `tesseraql token --url <origin>` works and
  the deploy endpoint validates bearers. Members deliberately kept their own `security.jwt`, and
  that slice's shipped note says plainly that issuer unification belongs to this campaign.
  Decision 9 takes it up.
- **The per-application role model shipped end to end**
  ([application-roles.md](application-roles.md)): principals carry store-resolved `roleGrants`,
  activation narrows a request to one active view per tab (`/_as/<role>`), `SessionTokens` mints
  an active view with an `acting_role` claim for `token --as`, and `TQL-SEC-4148` answers the
  wrong-capacity case. Decision 4's contract paragraph — written into this document by that
  campaign — now binds to shipped machinery rather than to a design.
- **The `security` migration hoist**: the host migrates the `security` component once, before any
  runtime starts, and hosted runtimes validate. Decision 3's "under the same lock the migration
  takes" now names a shipped mechanism rather than asking for one.
- **audit-hardening slices 6 and 7 are this campaign's to deliver.** `stack-architecture.md`
  Decision 2 widened their deferral condition to "a deployment that puts MCP in front of users who
  are not developers" — which is exactly who this server exists for. An authorization server
  nobody is told about serves nobody: the resource-side metadata and the MCP transport gate are
  sequenced in as slice 10, built to `audit-hardening.md`'s own decisions.

One standing instruction carries over from `stack-architecture.md` Decision 5 and gains force with
the start of implementation: **re-verify CXF's advisory record against primary sources at each
slice**, and track the version rather than pinning and forgetting it. The 2026 record is the price
of the no-second-process axis, and it is paid by attention.

## Decisions

### 1. The module takes CXF's grant layer and writes its own endpoints

Measured against CXF 4.2.3 in `stack-architecture.md` Decision 5: the endpoint package is 18 of 21
classes bound to JAX-RS, the grant layer needs only the `jakarta.ws.rs-api` jar, and of five grant
families this needs `code` and `refresh`.

The new module is **`tesseraql-oauth`**, mirroring `tesseraql-oidc`'s shape: it depends on
`tesseraql-core`, `tesseraql-compiler` (the `RuntimeExtension` seam), `tesseraql-identity`,
`tesseraql-security`, plus CXF's `cxf-rt-rs-security-oauth2` and `jakarta.ws.rs-api`. **It must not
depend on `tesseraql-runtime`**, which is the rule `stack-architecture.md` Decision 15
records; the three existing framework-surface modules already satisfy it, and `tesseraql-oidc`
proves the extension seam can mount routes without it. This module joins them rather than becoming
the exception.

CXF's own `services` classes are not used, and neither are its storage implementations —
`JPACodeDataProvider`, `JCacheCodeDataProvider`, `DefaultEncryptingCodeDataProvider`. That is not
merely a preference: those are the classes carrying several of the 2026 advisories, so declining
them is the structural half of the mitigation Decision 5 promised. The other half is tracking the
CXF version rather than pinning and forgetting it, which is the campaign's standing work.

*Shipped with slice 2 (2026-08-19), with the dependency floor measured rather than estimated.
CXF 4.2.3 was re-verified as the latest release with no advisories beyond the August batch it
fixes. The published POM makes the JAX-RS runtime a **compile** dependency, so the exclusion is
explicit: `cxf-rt-frontend-jaxrs`, `cxf-rt-rs-client`, `cxf-rt-rs-security-jose-jaxrs` and
`openjpa` are excluded, and the real floor is `jakarta.ws.rs-api`, `cxf-core` and
`cxf-rt-rs-security-jose` — the JOSE **library** is demanded by the class verifier because
`OAuthUtils` carries JOSE types in method signatures, and the JPA **API** jar rides
compile-only because CXF's model classes are annotated with it. The unit suite drives the real
handlers on exactly that classpath, so a regression of the floor fails the build rather than a
deployment.*

### 2. The store holds codes, refresh tokens, clients and consents — and not access tokens

The interface to implement is **twelve methods**, verified against 4.2.3 rather than taken from the
earlier estimate of seven: `OAuthDataProvider` declares nine abstract methods and one default, and
`AuthorizationCodeDataProvider` extends it with three more. Every signature is plain Java; none
carries a JAX-RS type.

| Method | Backed by |
| --- | --- |
| `getClient` | the client registry (Decision 5) |
| `createCodeGrant`, `removeCodeGrant`, `getCodeGrants` | the authorization-code table, single-use |
| `createAccessToken`, `refreshAccessToken` | minting (Decision 3); refresh rotation writes the store |
| `getRefreshTokens`, `revokeToken` | the refresh-token table |
| `getPreauthorizedToken` | returns null — see Decision 4 |
| `getAccessTokens` | see below |
| `convertScopeToPermissions` | returns nothing — Decision 11 of `stack-architecture.md` |

**The conflict this design expected between CXF's store-shaped contract and Decision 9's stateless
access tokens does not exist.** The spike measured it against the 4.2.3 sources on 2026-08-16: the
whole `grants` tree calls exactly **four** data-provider methods —

    createAccessToken   ×2      refreshAccessToken   ×1      getPreauthorizedToken   ×1

— and **`getAccessToken` and `getAccessTokens` are never among them**. Their only caller outside
the `services` package this build replaces is `tokens/hawk/HawkAccessTokenValidator`, for a MAC
scheme this does not use. Everything else a naive search turns up is a different method of the same
name: `OAuthClientUtils.getAccessToken(WebClient, …)` on the client side, and
`RefreshToken.getAccessTokens()`, which is a property of the model rather than a call into the
store.

So twelve methods must be **implemented**, because the interface says so, and four are ever
**exercised**. The rest are honest refusals with a recorded reason rather than machinery: nothing
enumerates tokens this server does not keep.

One caveat that belongs here rather than in a future surprise: **`getAccessToken` acquires a caller
the moment token introspection (RFC 7662) or token revocation (RFC 7009) is added**, because both
look a token up by its value. Neither is built — sign-out revokes refresh tokens server-side without
an endpoint — but if either arrives, this is the method that has to grow an answer, and
reconstructing a `ServerAccessToken` from validated JWT claims is how it should be answered.

*Slice 5 shipped 2026-08-19: `POST /_tesseraql/oauth/token` with the `code` and `refresh`
handlers over this provider — client authentication first (Basic or form credentials against
the stored hash in constant time; a public client presents its id alone), OAuth's wire
vocabulary on every refusal, `Cache-Control: no-store` on every answer. The mint gained the
re-resolution decision 4 contracted: a `SubjectView` seam asks the identity store for the
subject's **current** view at every mint — code redemption and refresh alike — so grant
changes, validity windows and rule recomputation propagate at refresh cadence, a disabled
account stops refreshing, and a revoked acting role ends the connection's capacity as
`invalid_grant`. Claims carry the stack's own claim names (the same config the derived member
blocks read) and the origin as `iss` — the mint-side half of the derived validation, caught by
the end-to-end test when it was missing. The lifetime placeholders stay
`tesseraql.security.oauth.accessTokenTtl`/`refreshTokenTtl` in the configuration reference;
the operator cookbook page rides the metadata slice, where the surface becomes discoverable.*

The tables ride the **`security` migration component**, beside sessions — which the hoist has the
host migrate once for the whole stack, before any runtime starts. The store implementation reads
the framework datasource through the extension seam (`ExtensionContext.frameworkDataSource()`),
which is the same road `OidcStateStore` already travels.

*Shipped with slice 2 (2026-08-19): `tesseraql-oauth` with the twelve-method
`TesseraqlOAuthDataProvider` over an `OAuthStore` seam (`JdbcOAuthStore` on the V5 tables,
proven by a Testcontainers suite; the in-memory twin drives CXF's real handlers in unit tests).
Codes and refresh tokens land only as SHA-256 hashes; consume and rotation are guarded
single-winner writes, which is what closes in our own storage the TOCTOU race CXF shipped as
CVE-2026-50631. One mechanical finding worth keeping: the handlers validate a grant's audience
against the client's **registered** audiences, and a DCR client registers none — so the
RFC 8707 resource rides the grant's extra properties end to end, and which resources a subject
may reach stays the authorize endpoint's question, exactly where Decision 4 put it.*

### 3. Signing keys live in the framework datasource, so every replica serves one JWKS

`stack-architecture.md` Decision 8 overturns `session-token-exchange.md`'s premise that no private
key exists in the tree. Where it lives is this document's to decide.

**The framework datasource**, beside the sessions the `security` migration component already owns.
The precedent is Keycloak's, recorded in `authorization-server.md`: signing keys in the database is
what lets every node publish one key set. It also matches TesseraQL's own model — every replica
identical, the database arbitrates — which is the property Decision 4 chose building over adopting
to keep.

A key pair is generated on first start if none exists, at the host's one migration moment — the
hoist gives the stack exactly one actor touching the `security` schema before runtimes exist, so
concurrent first starts cannot produce two pairs.

**Rotation is overlapping and `kid`-addressed**: a new key becomes the signer while the previous
one stays published until every token it signed has expired, which is bounded by the access-token
lifetime rather than by the refresh-token lifetime, because refresh tokens are opaque to the
resource server and validated by the store. That bound is the reason rotation is affordable at all,
and it should be stated in the operator documentation rather than left to be discovered.

*Shipped with slice 3 (2026-08-19), with one strengthening: generation does not lean on the
host's migration moment after all — the first key's `kid` is the reserved primary key
`initial`, so concurrent first starts race on the row and exactly one generates, which holds for
any number of replicas rather than for one blessed actor. Rotation inserts the new signer before
retiring strictly older keys, so the stack is never keyless and two concurrent rotations leave
two live keys rather than zero. The JWKS is served at `/_tesseraql/oauth/jwks` — inside the
shipped origin fence, so no relay change was needed; the `/.well-known/*` fence gain travels
with the metadata slice that serves documents there. `Rs256TokenSigner` fills the signer seam
(`kid` in the header), and the round trip is proven against `Jwks`, the exact parser member
bearer validation uses — the interop slice 9 stands on. The extension ships in the runtime like
Studio (a framework surface, not an opt-in module), inert wherever the stack file's graft does
not reach; a member carrying the key is refused at boot with `TQL-OAUTH-3000` — Decision 10's
domain, minted — and the lint half of Decision 8's refusal lands with the authorize slice.*

### 4. Authentication and consent reuse what exists; consent is per client and per resource

`/authorize` asks whether the caller holds a session and does nothing else about identity. There is
no new authentication path: `tesseraql_sid` is the answer however it was obtained — the framework's
own login, OIDC, or SAML — and its absence redirects to `/_tesseraql/login?redirect=…`, which
already carries a caller back to where it was going.

Consent is a page in the same surface, and `stack-architecture.md` Decision 10 fixes its three
properties. It is **mandatory**, because registration is open. Client-supplied metadata is
**display text chosen by the party asking to be authorised**, rendered escaped and never presented
as though the framework vouched for it. And it is recorded **per client and per resource**, so
consenting to one application in a stack is not consenting to the rest.

**Consent is answered at `/authorize`, which is ours, not inside the grant layer.**
`getPreauthorizedToken` is not the consent hook it first looks like: `AbstractGrantHandler` calls it
to reuse a *previously issued access token* rather than to skip a screen, and a server that stores
no access tokens has none to return. It returns null, which is that method's ordinary answer and
means "mint a new one". The consent record is read by our authorize endpoint before any grant runs.

`getRefreshTokens(Client, UserSubject)` is what a subject-facing "applications you have authorised"
page reads. The account surface (Decision 14) is where that page belongs, and revoking there deletes
the consent and the refresh tokens together.

*Slice 8 shipped 2026-08-19: `/_tesseraql/account/connections` lists the subject's consents per
client and per resource — client name escaped, resource and member named, the selected capacity
and the live-token count beside them — and its revoke deletes the consent and revokes every
matching refresh chain in one act, so the next refresh is `invalid_grant` and the next authorize
owes the consent screen again. The providers are registered unconditionally and answer
`enabled: false` wherever the oauth extension bound no store, because the account app also
mounts on unhosted runtimes and the page renders that state honestly. The page reads the store
directly rather than through `getRefreshTokens`' hash-keyed models, exactly as Decision 2's
listing note anticipated.*

**The consent screen is also where a concurrent-role user selects an acting role** — the OAuth face of the
role activation designed in [application-roles.md](application-roles.md) (its "three faces"
section). The authorize endpoint holds the store-resolved role grants and the `resource`
parameter's member, so it renders the acting-role selection beside consent for a user holding
several of that application's roles (a single holder auto-confirms), records the narrowing on
the consent and its refresh tokens so refreshed access tokens keep the capacity (an
`acting_role` claim beside the active view's roles and permissions), and re-resolves the store
at refresh so grant changes, validity windows and rule recomputation propagate at refresh
cadence. Changing a connection's capacity is a re-authorization. Client-requested scopes are
deliberately not the carrier — the measured MCP clients let no user type a scope and their
scope-sending behavior is unobserved; the server-side selection depends on neither. This lands
with the authorize/consent slice.

That contract now binds to shipped machinery, not to a design: the store-resolved `roleGrants`
already ride every principal, the single-holder auto-confirm is the browser flow's own
one-role-302 in consent form, `SessionTokens` already demonstrates the mint-from-active-view
computation with its `acting_role` claim, and the refresh-time re-resolution reads the same store
the member's activate step reads per request. The consent screen adds no second computation — it
is the picker's OAuth face, selecting from the union and minting from the active view.

PKCE is **required, `S256` only**, and `AuthorizationCodeGrantHandler` supports exactly that without
modification: `setRequireCodeVerifier(true)` demands a verifier from every client rather than only
public ones, and the transformer list decides which challenge methods are accepted. CXF ships
`DigestCodeVerifier` (S256) as the default and `PlainCodeVerifier` beside it; registering only the
first refuses `plain` rather than downgrading to it. Single-use codes come free — the handler calls
`removeCodeGrant` as it reads.

*Shipped with slice 4 (2026-08-19), as one validation ladder (`AuthorizeFlow`) behind three
surfaces so they cannot drift: the protocol `GET /_tesseraql/oauth/authorize` and the consent
decision `POST /_tesseraql/oauth/decision` in the extension, the consent screen as an auth-ui
page between them (`/_tesseraql/oauth/consent` — the split open question 6 chose, made literal
because a compiled page cannot 302 to a dynamic external callback, and because the same path
cannot carry a Java POST and a YAML GET without answering 405). The ladder: an unknown client
or a mismatched redirect URI answers on the page and is never redirected to; a missing or
non-`S256` challenge, a missing or foreign `resource` (`invalid_target` — the fail-loud
placeholder), and a denial answer on the wire; a recorded consent for the client and resource
skips the screen and answers with the code; approval records consent — the selected capacity
riding it — and redirects with the code, the echoed `state`, and RFC 9207's `iss`. The
acting-role face works as contracted: held roles for the resource's member render as the
selector, a single holder is pre-selected, an unheld selection is `access_denied`, and the
capacity lands in the code grant's subject so slice 5's minted tokens carry it. Deferred
knowingly: the lint half of Decision 8's app-declared-key refusal (the member boot refusal
stands) now travels with the register slice, and refresh re-resolution of grants stays with
slice 5 where refresh lives.*

### 5. Registration is open, and the registry is small

Open, because `stack-architecture.md` Decision 3 established that MCP clients cannot present an
initial access token — gating registration means not being reachable.

The registry stores what a client sent, what it was issued, and when it was last used: client id and
secret hash, redirect URIs, the metadata treated as display text, the registration timestamp, and a
last-seen stamp so that an operator can find registrations nothing ever used. Nothing about a client
is trusted beyond its redirect URIs, which are matched, and its credentials, which are verified.

**Redirect-URI validation is exact match, measured rather than guessed.** The 2026-08-19 Codex
observation (open question 2) settled what `stack-architecture.md` Decision 3 left open: Codex
registers the complete callback — `http://127.0.0.1:<ephemeral port>/callback/<callback_id>` by
default, `<configured base>/<callback_id>` under a custom `mcp_oauth_callback_url` (the
`/callback/` segment is not always appended) — and sends the **same complete URI** in
`redirect_uris` at registration and in `redirect_uri` at `/authorize`; a retry re-registers with
its new ephemeral port. So exact match against the registered URIs rejects nothing that matters —
the "exact match will fail" expectation conflated the appended path with a mismatch, when the
appended path is registered too — and the weaker prefix alternative is not adopted. Two
consequences belong to this slice: registration churn is by design (a new ephemeral port is a new
registration), which is what the last-seen stamp exists for; and consent stays per client under
that churn — the cost of open registration Decision 10 already accepted, not a reason to weaken
either rule.

Bounding registration spam belongs to the ingress, per `stack-architecture.md` Decision 13's
division of the gateway from the thing in front of it.

*Slice 6 shipped 2026-08-19: `POST /_tesseraql/oauth/register` (RFC 7591), open and
unauthenticated, storing exactly what Decision 5 lists — the metadata as display text, the
complete redirect URIs matched exactly thereafter (the measured shape), the registration
stamp, the last-seen stamp `getClient` keeps fresh. The default `token_endpoint_auth_method`
is `none`: the measured population is native loopback clients with no secret storage; a client
that asks for `client_secret_basic` is issued a secret it sees once, stored hashed. The lint
half of Decision 8's refusal landed here too — `TQL-OAUTH-3004` tells an application declaring
`tesseraql.security.oauth.enabled` in its own tree that the key is the stack file's, before
the member boot refusal would.*

### 6. Metadata is served at the origin, and the resource metadata is the application's

`stack-architecture.md` Decision 6 makes the issuer the stack origin, with no path component, so
RFC 8414's insertion rule does not apply and authorization-server metadata sits at the bare
`/.well-known/oauth-authorization-server`. The endpoints it advertises — `/_tesseraql/oauth/authorize`,
`/token`, `/register` — are listed explicitly and need not share the issuer's path.

Serving that path is a one-line consequence the shipped relay makes visible: the origin fence
forwards `/_tesseraql/*` and `/assets/*` to the surface runtime today, and `root-portal.md`
reserved `/.well-known/*` for this document — the fence gains that prefix in the metadata slice,
and nothing else about routing moves.

*Slice 7 shipped 2026-08-19: the fence gained `/.well-known/*` (the segment starts with a dot,
so the address grammar already kept member names clear of it), and the extension serves RFC
8414 metadata at the bare well-known — issuer, the four absolute endpoints, `code` only,
`S256` only, `none` and `client_secret_basic`, and deliberately no `scopes_supported`, as
measured. RFC 9207's `iss` had already shipped with the authorize slice. The
`authorization_servers` value the resource metadata will carry is the issuer; the resource
side itself is slice 10's.*

RFC 9207's `iss` is included in the authorization response. It is cheap now and awkward later, and
it costs nothing to a client that ignores it.

Protected-resource metadata is **not this module's**. It describes an application's MCP surface, is
produced by that application's runtime, and is relayed by the gateway — `stack-architecture.md`
Decision 18. This module supplies only the issuer value that appears in the `authorization_servers`
list. Building that resource side is nevertheless this campaign's work — slice 10, to
`audit-hardening.md`'s own decisions, because its deferral condition is met by this server's own
target audience (`stack-architecture.md` Decision 2). Note the path shape recorded there: RFC 9728
**inserts** the well-known segment between host and path, so a member's document lives at
`/.well-known/oauth-protected-resource/<member>/…` — origin scope, one more reason the fence owns
`/.well-known/*` and relays by suffix.

### 7. Brokering is a login mode, not a second design

Under `stack-architecture.md` Decision 7 case B the stack's identity comes from a customer's
existing provider. Nothing in this module changes: `/authorize` still asks for a session, and
`OidcRouteBuilder` still obtains one through authorization-code with PKCE against that provider.
The brokering is entirely upstream of the authorization server.

The rule that must be stated, because it is easy to reverse by accident: **the token carries
TesseraQL's roles and permissions, never the provider's assertions.** `OidcUserLinker` already holds
that line for federated logins, and `stack-architecture.md` Decision 11 restates it for tokens.

What this buys is the DCR proxy of Decision 7 — dynamic registration facing the MCP client, an
ordinary pre-registered client facing the enterprise provider — which is the configuration that
resolves the deadlock Decision 3 documented, without touching the customer's provider.

### 8. The endpoints mount on the stack surface runtime, and the stack file turns them on

*Added at campaign start, 2026-08-19 — the concrete form of "hosted the way Decision 14 hosts the
other framework surfaces", which was a plan when this document was written and is a shipped runtime
now.*

**The authorization server is a stack-scoped surface.** Its issuer is the stack origin (Decision
6), its subjects are the stack's users, and its resources are the stack's members — none of which
an unhosted single runtime has. So it mounts on the **stack surface runtime** — the origin-scope
runtime that already serves sign-in, the account surface and the portal — and nowhere else. The
module self-installs through the `RuntimeExtension` seam exactly as `tesseraql-oidc` does, keyed on
configuration only the surface runtime's config carries.

**Enablement is a stack-file declaration, off by default.** `stack-architecture.md` Decision 7
case A requires it: a component that issues credentials should exist because somebody decided it
should, which is `token.enabled`'s own reasoning at stack scope. The stack file's `security:`
subtree already grafts onto the surface runtime's config, so the switch is one more key in the same
place the issuer's other settings live:

    # tesseraql-stack.yml
    security:
      oauth:
        enabled: true

Lifetimes and rotation settings join it under `security.oauth.*` as the slices need them, riding
the shipped graft rather than a new channel. **An application declaring the key itself is refused**
at lint and boot — the surface-only rule would otherwise be one config line from silently minting
a second issuer inside a member, and the shipped precedent is `TQL-APP-4212`'s: an explicit
declaration in the wrong scope is a refusal naming the right one, not a silent override.

The consent and authorize pages are served the way the surface's other pages are — on hc, behind
the origin fence, with the session already at hand. They live in the bundled `auth-ui`
application, beside sign-in (open question 6, closed on its recommendation); the protocol
endpoints, which have no page to declare, stay in the extension.

### 9. One issuer per stack: members validate the published key, and the exchange signs with the private one

*Added at campaign start, 2026-08-19. Resolves open question 5.*

The stack shells left the stack with a deliberate seam: the surface runtime validates and mints
HS256 from the stack file's `security.jwt.*`, while **members keep their own per-app `jwt` blocks
and their own secrets** — the shipped note says issuer unification belongs to this campaign. This
decision is that unification.

**When the authorization server is enabled, the stack has one issuer and it signs RS256.** Three
consequences, in dependency order:

- **Members validate the stack's published key.** The host grafts bearer-validation configuration
  onto every member: RS256, the stack origin as issuer, key material from the JWKS the surface
  publishes. The machinery exists — `JwtAuthenticator` has validated RS256 via `jwksUri` since the
  JWKS slice of the authentication phase — so this is configuration plumbing through
  `HostContext`, not a new validator. Each member's **audience stays its own**: `aud` is the
  boundary Decision 6 of `stack-architecture.md` makes real, and unifying the signer is precisely
  what makes distinct audiences meaningful.

  > **Refined 2026-08-24 (the Codex acceptance's first finding).** A member reads the derived
  > key set straight from the shared framework database — the rows the surface's JWKS renders —
  > never over HTTP. Once JWKS fetches rode the outbound gateway, fetching the member's own
  > public origin put every bearer validation behind the member's egress allow list and failed
  > it closed, silently, on any stack that had not allow-listed its own origin. The stack
  > reaching itself is not egress (`LoopbackCall`'s posture); an app-declared `jwksUri` — an
  > external IdP — still rides the gateway and its allow list, and a failed fetch now leaves a
  > rate-limited WARN either way. The HTTP document remains what external clients read.
- **A member's own explicit `security.jwt` while the stack issues is a refusal**, in `TQL-APP-4212`'s
  shape — naming the stack file as where the issuer lives. The alternative (member config silently
  outranked) is the divergence-fails-silently case Decision 16 exists to prevent; the alternative
  in the other direction (two issuers accepted in parallel) is Decision 7 case C, rejected there.
- **The session-token exchange signs with the same key.** `SessionTokens` — the one signer behind
  `POST /_tesseraql/token` and the console token page — gains RS256 signing with the authorization
  server's private key when the server is enabled. A token fetched by `tesseraql token --url` and a
  token minted through `/authorize` then verify against the same JWKS at the same members: **two
  acquisition paths, one issuer.** The exchange keeps its shape (session in, short-lived token out,
  no refresh tokens) because its audience — a developer at a terminal — has not changed; what
  changes is only whose key signs.

**`TQL-SEC-4146` therefore narrows rather than dies.** It was minted for "issuing was enabled and
there is nothing to sign with", correct while HS256 was the only signature TesseraQL could produce.
With the authorization server enabled there *is* something to sign with, so the refusal fires only
when issuing is enabled and neither an HS256 secret nor the stack's signing key exists. The
exchange and the authorization server stop being alternatives within one application — the framing
open question 5 carried from `stack-architecture.md` — and become two doors into one issuer.
`session-token-exchange.md`'s Decision 1 premise is edited when this slice ships, not before,
per the campaign rule that prose describing shipped behavior moves with the code.

**What a unified token carries** follows `stack-architecture.md` Decision 11 and the shipped role
model: `aud` is the resource identifier the grant was made for; roles and permissions are the
active view for **that member** — the acting-role narrowing when one was selected, the member's
union otherwise — never the subject's stack-wide authority. The exchange gains the member axis for
the same reason (a token that only the surface accepts serves deploys, not applications); its
parameter shape is open question 7, closed on its recommendation: `--app-name` and a page
selector, defaulting to the surface's own audience.

*The unification core shipped 2026-08-19 (slice 9's first half). The host derives one
validation block from the stack file — RS256, the origin as issuer, the surface's JWKS, the
stack's claim names — and every hosted runtime applies it; nothing is declared per member, and
a declared key source is refused as a second issuer (`TQL-OAUTH-3001`; the origin itself is
required by `TQL-OAUTH-3002`). One call the design paragraph above forced into the open: a
runtime's accepted audiences are **its own address plus the stack origin**. The origin is the
exchange's stack-wide mint — a bearer with exactly the reach the session already has, which is
what `stack-architecture.md` Decision 27 says a stack is — while an address-scoped audience is
the OAuth grants' per-member boundary, so a token granted for one member still refuses at the
next. `SessionTokens` signs through the extension's RS256 signer wherever
`security.oauth.enabled` reaches, `TQL-SEC-4146` narrowed as decided, and
`session-token-exchange.md` Decision 1 carries the premise correction.*

*The member axis followed the same day, completing the slice: `tesseraql token --url --app-name
<member>` and the console page's application selector mint that member's address as the
audience and the member's active view under the browser's own entry rules per token — one held
role auto-activates, several stay inactive unless `--as` selects one, an unheld role is
`TQL-SEC-4148` and an unaddressed member `TQL-OAUTH-3003`. Nothing named keeps today's
stack-wide mint verbatim. One refinement the integration test forced: a runtime's **address is
always accepted** beside anything it declared — the address and the origin are the issuer's
vocabulary, not the application's, so a declared audience joins them rather than replacing
them.*

### 10. Protocol failures speak OAuth on the wire and `TQL-OAUTH` in the logs

*Added at campaign start, 2026-08-19. Resolves open question 4.*

OAuth prescribes its own error vocabulary — `invalid_grant`, `invalid_client`, `invalid_target`,
redirect-carried error codes — and the wire answers are RFC-shaped regardless of what TesseraQL
calls them internally. The open question was only what the logs and the error index say.

**A new domain: `TQL-OAUTH`.** The per-module precedent is uniform — the OIDC relying party,
SAML, SCIM each brought their own domain — and `tesseraql-oauth` is a module of the same standing.
Reusing `TQL-SEC` was the thriftier option and loses on the property that decides it: `TQL-SEC`
codes answer "the framework refused *your* credential", while the authorization server's failures
answer "a protocol exchange between a client and this server went wrong" — different audience,
different remedy, and an operator grepping an incident should not have to learn which 41xx numbers
changed meaning. The domain joins `TqlDomain`, the reference index picks it up through the ordinary
machinery, and the code space starts clean at 3000-series config / 4000-series refusals like its
sibling modules.

### 11. Done means a real client connects

The campaign's acceptance is not a green unit suite over grant handlers; it is the chain
`stack-architecture.md` built toward: **a business user on a measured MCP client — ChatGPT
Desktop, Codex CLI, Claude Code — connects to a member's MCP surface through discovery,
registration, consent and refresh, with no fixed credential and no developer in the loop.** The
connect-and-observe pass is therefore not optional homework: it is both the measurement slices
were gated on and the campaign's own finish line. Its Codex leg ran on 2026-08-19 and closed open
questions 2 and 3; its Claude Code leg ran the same day, against a logging stub of this design's
own document shapes, and took the RFC 8707 `resource` observation (open question 2's note). What
remained for the finish line — the interactive half — ran the same day: a live stack (`dev`,
the authorization server enabled, an `identity-schema`-seeded administrator), `claude mcp add`
+ `claude mcp login`, a person signing in and approving on the shipped consent screen, and the
client landing `✔ Connected` on the gated MCP surface with the minted token. The account
connections page listed the live connection, name escaped, revocation an act away. **The
acceptance this decision defines is met for Claude Code**; Codex and ChatGPT Desktop remain
unexercised end to end, with every measured behavior of Codex already accounted for.

## Open questions

1. ~~**Whether `getAccessToken` can reconstruct rather than retrieve.**~~ **Closed 2026-08-16 by the
   slice 1 spike: the question does not arise.** `grants` never calls it. Decision 2 records the
   measurement, and the risk that this would send the design back to Spring Authorization Server is
   retired.
2. ~~**The redirect-URI shape Codex actually registers**~~ — **measured 2026-08-19: exact match
   survives.** Codex registers `http://127.0.0.1:<ephemeral port>/callback/<callback_id>` (or
   `<configured base>/<callback_id>` under a custom `mcp_oauth_callback_url`) and sends the same
   complete URI at `/authorize`, re-registering on retry with its new port. Decision 5 records the
   consequences; slice 6 is unblocked. **The `resource` observation was taken 2026-08-19,
   against Claude Code itself** — driven at a logging stub serving this design's exact document
   shapes: Claude Code fetched the authorization-server metadata and the **path-inserted**
   protected-resource document, registered with a complete loopback callback
   (`http://localhost:<ephemeral>/callback` — exact match generalizes beyond Codex), asked for
   `token_endpoint_auth_method: none` explicitly (the default slice 6 chose), and built an
   authorize URL carrying `S256` **and `resource=` — the protected-resource document's
   identifier, verbatim**. The fail-loud `invalid_target` placeholder therefore stands as the
   behavior, not a placeholder: the measured client sends `resource`, so requiring it refuses
   nothing that matters. Codex's own `resource` behavior stays unobserved; the MCP specification
   requires it and the observed client complies. The older field evidence (claude.ai omitting
   it) predates that requirement and concerns a hosted connector this does not target.
3. ~~**Whether any client refuses to proceed without `scopes_supported`**~~ — **measured
   2026-08-19 for Codex: it does not.** Against metadata omitting `scopes_supported` entirely,
   Codex registered and built the authorization URL, and — with no scopes configured on its side —
   sent no `scope` parameter at all; its documented behavior prefers `scopes_supported` when
   present and falls back to its own configuration otherwise. Decision 11 of
   `stack-architecture.md` stands. The answer is per-client rather than universal: Claude Code and
   ChatGPT Desktop are observed when the acceptance chain runs against them.
4. ~~**The error-code range.**~~ **Closed 2026-08-19 — Decision 10: a new `TQL-OAUTH` domain.**
5. ~~**What `TQL-SEC-4146` becomes.**~~ **Closed 2026-08-19 — Decision 9: it narrows.** The
   exchange signs with the authorization server's key when the server is enabled; the refusal keeps
   firing only when issuing is enabled and no key material of either kind exists.
6. ~~**Where the consent and authorize pages live**~~ — **closed 2026-08-19 on the
   recommendation: `auth-ui`.** Consent is a page a person reads beside sign-in; the app already
   carries the hc login markup, and its lint and coverage ride the declarative surface. The
   protocol endpoints (`/token`, `/register`) stay in the extension, where no page exists to
   declare. The rejected alternative — route builders owned entirely by `tesseraql-oauth`, the
   OIDC module's shape — is kept here because it is the one a reader would propose again.
7. ~~**The exchange's member axis after unification**~~ — **closed 2026-08-19 on the
   recommendation.** `tesseraql token --url <origin>` gains `--app-name <member>` and the console
   token page gains an application selector over the caller's entitled members, minting that
   member's audience and that member's active view — the same computation Decision 9 fixes for the
   authorization server. Nothing named keeps today's behavior verbatim: the surface's own
   audience.
8. ~~**Refresh-token lifetimes and rotation windows**~~ — **closed 2026-08-19 on the
   recommendation: placeholder defaults ship with slice 5**, named in the operator documentation,
   and the numbers (idle expiry, absolute expiry, the access-token lifetime the JWKS rotation
   overlap is bounded by) are tuned against the measured clients' refresh behavior once the
   observe pass runs against a live stack. Rotation-on-use and reuse detection were never open —
   Decision 9 of `stack-architecture.md` fixed them.

## Slices

Numbering is stable — external references to "slice 6" stay true — and the sequence is the
dependency order given below, not the numeric one.

| # | Slice |
| --- | --- |
| 1 | ~~Spike: `getAccessToken` reconstruction against CXF's `code` and `refresh` flows~~ — **done 2026-08-16**, and it retired its own question |
| 2 | ~~The `tesseraql-oauth` module, storage schema on the `security` migration component, the twelve-method provider proven against CXF's `code` and `refresh` grant flows in unit tests~~ — **shipped 2026-08-19**; the measured corrections live in Decisions 1 and 2 |
| 3 | ~~Signing keys in the framework datasource, generation on first start, JWKS publication at the origin, `kid` rotation~~ — **shipped 2026-08-19**; the strengthened generation guard and the fence deferral are in Decision 3's note |
| 4 | ~~`/authorize` over the existing session, the consent page with the acting-role selection (Decision 4's contract), consent persistence per client and per resource, `S256`-only PKCE~~ — **shipped 2026-08-19**; the three-surface split and the deferrals are in Decision 4's note |
| 5 | ~~`/token` with authorization-code and refresh grants, refresh rotation with reuse detection retiring the chain~~ — **shipped 2026-08-19**; the mint-time re-resolution is in Decision 2's note |
| 6 | ~~`/register`, the client registry, exact-match redirect validation (measured — open question 2)~~ — **shipped 2026-08-19**, with Decision 8's lint half (`TQL-OAUTH-3004`); Decision 5's note has the auth-method default |
| 7 | ~~RFC 8414 metadata at the bare well-known, RFC 9207 `iss`, and the `authorization_servers` issuer value the resource metadata will carry~~ — **shipped 2026-08-19** (Decision 6's note; `iss` had shipped with slice 4) |
| 8 | ~~Account-surface page: applications authorised, and revocation that deletes consent and refresh tokens together~~ — **shipped 2026-08-19** (Decision 4's note) |
| 9 | ~~Issuer unification (Decision 9): members validate the stack JWKS, the explicit-member-jwt refusal, `SessionTokens` signs RS256, `TQL-SEC-4146` narrows, the exchange's member axis, the `session-token-exchange.md` premise edit~~ — **shipped 2026-08-19** (Decision 9's notes) |
| 10 | ~~The resource side, to `audit-hardening.md`'s decisions: RFC 9728 protected-resource metadata per member at origin scope, the `WWW-Authenticate` challenge, the MCP transport gate and audience binding~~ — **shipped 2026-08-19**; what remains of the campaign is the acceptance run against the real clients (Decision 11) and the RFC 8707 `resource` observation. Three deviations recorded: the surface serves the per-member metadata itself rather than relaying an app-produced document (`stack-architecture.md` Decision 18 predates the surface runtime; the document's whole content — resource id and issuer — is the surface's knowledge), `tesseraql.mcp.auth` serves `public` and `bearer` at the gate for now (`TQL-MCP-4262` names anything else; per-primitive auth runs underneath either), and the challenge carries `resource_metadata` only where an origin makes the URL absolute — an unhosted gate answers bare `Bearer`, which an intranet caller does not need more of |

**Sequence: 2 → 3 → 9 → 4 → 5 → 6 → 7 → 10 → 8.** Slice 9 lands third because it is where the
stack first *uses* the new key material — `token --url` against a member API proves signing,
publication and member validation end to end before any OAuth endpoint exists, and every later
slice then ships against a stack whose tokens already work. Slices 6 and 7 close the client-facing
chain, 10 makes it discoverable and runs the acceptance of Decision 11, and 8 completes the
subject-facing story and can land any time after 5.

Slice 1 was a spike rather than a slice: its output was a finding, and the finding decided whether
slices 2 onward are written against CXF at all. **They are** — and the store surface is smaller than
the interface implied, which is recorded in Decision 2 rather than left to be rediscovered.
