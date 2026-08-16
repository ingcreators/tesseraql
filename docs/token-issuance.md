# Token issuance

Status: **designed 2026-08-16** — nothing shipped. This is the implementation design for the
authorization server [suite-architecture.md](suite-architecture.md) decided to build. That document
decided *whether* and *what for*; this one decides *how*, and stops where a measurement it does not
have would decide better.

**What it implements.** Decisions 4 through 11 of `suite-architecture.md`: an authorization server
for TesseraQL's own users, colocated with the resource server, signing RS256, issuing refresh
tokens, registering clients dynamically, and requiring consent. It is a suite-level surface at
`/_tesseraql/oauth`, hosted the way Decision 14 hosts the other framework surfaces.

**What it is not.** Not a general identity provider — no SAML brokering as a product, no federation
beyond Decision 7 case B, no MFA. `authorization-server.md` records why each of those returns
Keycloak's argument, and that document's survey of alternatives stands; only its headline decision
was reversed.

## Decisions

### 1. The module takes CXF's grant layer and writes its own endpoints

Measured against CXF 4.2.3 in `suite-architecture.md` Decision 5: the endpoint package is 18 of 21
classes bound to JAX-RS, the grant layer needs only the `jakarta.ws.rs-api` jar, and of five grant
families this needs `code` and `refresh`.

A new module — sibling to `tesseraql-oidc`, `tesseraql-security` and `tesseraql-identity` — depends
on `tesseraql-core`, `tesseraql-yaml`, `tesseraql-security`, CXF's `cxf-rt-rs-security-oauth2` and
`jakarta.ws.rs-api`. **It must not depend on `tesseraql-camel-runtime`**, which is the rule
`suite-architecture.md` Decision 15 records and the build check it asks for; the three existing
framework-surface modules already satisfy it, and this one joins them rather than becoming the
exception.

CXF's own `services` classes are not used, and neither are its storage implementations —
`JPACodeDataProvider`, `JCacheCodeDataProvider`, `DefaultEncryptingCodeDataProvider`. That is not
merely a preference: those are the classes carrying several of the 2026 advisories, so declining
them is the structural half of the mitigation Decision 5 promised. The other half is tracking the
CXF version rather than pinning and forgetting it, which belongs in the campaign's standing work.

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
| `convertScopeToPermissions` | returns nothing — Decision 11 of `suite-architecture.md` |

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

### 3. Signing keys live in the framework datasource, so every replica serves one JWKS

`suite-architecture.md` Decision 8 overturns `session-token-exchange.md`'s premise that no private
key exists in the tree. Where it lives is this document's to decide.

**The framework datasource**, beside the sessions the `security` migration component already owns.
The precedent is Keycloak's, recorded in `authorization-server.md`: signing keys in the database is
what lets every node publish one key set. It also matches TesseraQL's own model — every replica
identical, the database arbitrates — which is the property Decision 4 chose building over adopting
to keep.

A key pair is generated on first start if none exists, under the same lock the migration takes, so
concurrent first starts cannot produce two.

**Rotation is overlapping and `kid`-addressed**: a new key becomes the signer while the previous
one stays published until every token it signed has expired, which is bounded by the access-token
lifetime rather than by the refresh-token lifetime, because refresh tokens are opaque to the
resource server and validated by the store. That bound is the reason rotation is affordable at all,
and it should be stated in the operator documentation rather than left to be discovered.

### 4. Authentication and consent reuse what exists; consent is per client and per resource

`/authorize` asks whether the caller holds a session and does nothing else about identity. There is
no new authentication path: `tesseraql_sid` is the answer however it was obtained — the framework's
own login, OIDC, or SAML — and its absence redirects to `/_tesseraql/login?next=…`, which already
carries a caller back to where it was going.

Consent is a page in the same surface, and `suite-architecture.md` Decision 10 fixes its three
properties. It is **mandatory**, because registration is open. Client-supplied metadata is
**display text chosen by the party asking to be authorised**, rendered escaped and never presented
as though the framework vouched for it. And it is recorded **per client and per resource**, so
consenting to one application in a suite is not consenting to the rest.

**Consent is answered at `/authorize`, which is ours, not inside the grant layer.**
`getPreauthorizedToken` is not the consent hook it first looks like: `AbstractGrantHandler` calls it
to reuse a *previously issued access token* rather than to skip a screen, and a server that stores
no access tokens has none to return. It returns null, which is that method's ordinary answer and
means "mint a new one". The consent record is read by our authorize endpoint before any grant runs.

`getRefreshTokens(Client, UserSubject)` is what a subject-facing "applications you have authorised"
page reads. The account surface (Decision 14) is where that page belongs, and revoking there deletes
the consent and the refresh tokens together.

PKCE is **required, `S256` only**, and `AuthorizationCodeGrantHandler` supports exactly that without
modification: `setRequireCodeVerifier(true)` demands a verifier from every client rather than only
public ones, and the transformer list decides which challenge methods are accepted. CXF ships
`DigestCodeVerifier` (S256) as the default and `PlainCodeVerifier` beside it; registering only the
first refuses `plain` rather than downgrading to it. Single-use codes come free — the handler calls
`removeCodeGrant` as it reads.

### 5. Registration is open, and the registry is small

Open, because `suite-architecture.md` Decision 3 established that MCP clients cannot present an
initial access token — gating registration means not being reachable.

The registry stores what a client sent, what it was issued, and when it was last used: client id and
secret hash, redirect URIs, the metadata treated as display text, the registration timestamp, and a
last-seen stamp so that an operator can find registrations nothing ever used. Nothing about a client
is trusted beyond its redirect URIs, which are matched, and its credentials, which are verified.

**Redirect-URI validation is deliberately unfinished.** `suite-architecture.md` Decision 3 records
that Codex appends `/callback/<callback_id>` to the configured callback, so exact-match validation
will reject it, and the measurement that would show the exact shape has not been taken. The
placeholder is exact match; the alternative is prefix match confined to a registered origin and
path, which is weaker and must not be adopted on a guess. Open question 2.

Bounding registration spam belongs to the ingress, per `suite-architecture.md` Decision 13's
division of the gateway from the thing in front of it.

### 6. Metadata is served at the origin, and the resource metadata is the application's

`suite-architecture.md` Decision 6 makes the issuer the suite origin, with no path component, so
RFC 8414's insertion rule does not apply and authorization-server metadata sits at the bare
`/.well-known/oauth-authorization-server`. The endpoints it advertises — `/_tesseraql/oauth/authorize`,
`/token`, `/register` — are listed explicitly and need not share the issuer's path.

RFC 9207's `iss` is included in the authorization response. It is cheap now and awkward later, and
it costs nothing to a client that ignores it.

Protected-resource metadata is **not this module's**. It describes an application's MCP surface, is
produced by that application's runtime, and is relayed by the gateway — `suite-architecture.md`
Decision 18. This module supplies only the issuer value that appears in the `authorization_servers`
list.

### 7. Brokering is a login mode, not a second design

Under `suite-architecture.md` Decision 7 case B the suite's identity comes from a customer's
existing provider. Nothing in this module changes: `/authorize` still asks for a session, and
`OidcRouteBuilder` still obtains one through authorization-code with PKCE against that provider.
The brokering is entirely upstream of the authorization server.

The rule that must be stated, because it is easy to reverse by accident: **the token carries
TesseraQL's roles and permissions, never the provider's assertions.** `OidcUserLinker` already holds
that line for federated logins, and `suite-architecture.md` Decision 11 restates it for tokens.

What this buys is the DCR proxy of Decision 7 — dynamic registration facing the MCP client, an
ordinary pre-registered client facing the enterprise provider — which is the configuration that
resolves the deadlock Decision 3 documented, without touching the customer's provider.

## Open questions

1. ~~**Whether `getAccessToken` can reconstruct rather than retrieve.**~~ **Closed 2026-08-16 by the
   slice 1 spike: the question does not arise.** `grants` never calls it. Decision 2 records the
   measurement, and the risk that this would send the design back to Spring Authorization Server is
   retired.
2. **The redirect-URI shape Codex actually registers** — *blocks Decision 5's validation.* Pending
   the connect-and-observe pass `suite-architecture.md` open question 6 describes; not runnable
   where this was written, since it needs the client and a browser.
3. **Whether any client refuses to proceed without `scopes_supported`** — same pass. Decision 11 of
   `suite-architecture.md` advertises none; this confirms the choice survives contact.
4. **The error-code range.** Reusing `TQL-SEC-` keeps one meaning per code in one registry; a new
   domain would separate protocol failures from framework authentication failures. OAuth returns its
   own error vocabulary on the wire regardless, so this decides only what the logs and the index say.
5. **What `TQL-SEC-4146` becomes**, carried over from `suite-architecture.md` open question 5. The
   refusal was correct while TesseraQL held no private key; once it does, the exchange and the
   authorization server are alternatives within one application rather than one being impossible.

## Slices

Sequenced so that the finding that could invalidate the approach is first.

| # | Slice |
| --- | --- |
| 1 | ~~Spike: `getAccessToken` reconstruction against CXF's `code` and `refresh` flows~~ — **done 2026-08-16**, and it retired its own question |
| 2 | Module, storage schema on the `security` migration component, the twelve-method provider |
| 3 | Signing keys in the framework datasource, generation under the migration lock, JWKS publication, `kid` rotation |
| 4 | `/authorize` over the existing session, consent page, consent persistence, `S256`-only PKCE |
| 5 | `/token` with authorization-code and refresh grants, refresh rotation with reuse detection |
| 6 | `/register`, the client registry, redirect validation once open question 2 answers |
| 7 | RFC 8414 metadata, RFC 9207 `iss`, and the `authorization_servers` value the gateway relays |
| 8 | Account-surface page: applications authorised, and revocation that deletes consent and refresh tokens together |

Slice 1 was a spike rather than a slice: its output was a finding, and the finding decided whether
slices 2 onward are written against CXF at all. **They are** — and the store surface is smaller than
the interface implied, which is recorded in Decision 2 rather than left to be rediscovered.
