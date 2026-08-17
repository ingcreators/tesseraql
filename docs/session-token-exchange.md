# Session-to-token exchange

Status: **designed and shipped 2026-08-15**; the path to the endpoint followed on 2026-08-16, as
slice 3.

An authenticated browser session can be exchanged for a short-lived bearer token. That is far less
than an authorization server: no `/authorize`, no consent screen, no client registry, no redirect
handling, and no new dependency. It is also enough for every client that runs on the user's own
machine, which is the set of clients an intranet deployment can actually reach.

This document exists because the alternative was priced first.
[authorization-server.md](authorization-server.md) asked whether TesseraQL should issue its own
tokens through a full OAuth authorization server and answered no, recording a session-to-token
exchange as its open question 3 — "worth doing for reasons that have nothing to do with MCP". This
closes that question.

## Why an authorization server is not what this needs

The value an authorization server adds, when the resource server owns authorization, is narrower
than its name suggests. It authenticates users and **issues tokens**; it does not make access
decisions. In TesseraQL those decisions belong to `PolicyEngine`, and
`OidcUserLinker` already states the rule for federated logins: authorization uses locally-managed
roles and permissions rather than IdP-asserted ones.

So for an operator who needs tokens and already has authorization, an authorization server buys one
thing — issuance — at the cost of a second process, a second admin surface, and a lifecycle to
maintain. It is worth that where it also carries credential custody, MFA, or single sign-on across
several applications. It is not worth it to obtain a signed subject.

**It also cannot be skipped where the hosted assistants are the target.** claude.ai and ChatGPT's
hosted connectors have no field anywhere to hand a client a fixed token, so OAuth discovery is the
only way in; that is [audit-hardening.md](audit-hardening.md) slices 6 and 7, and this document does
not replace them. What it replaces is the assumption that they are the *first* thing to build.

## What it is

`POST /_tesseraql/token` with an authenticated session returns a bearer token for this application,
carrying the caller's subject and the audience the application declares. The caller is already
authenticated — the exchange asserts nothing new about who they are.

Every claim the token carries is one the bearer path already reads: `sub`, the configured login,
roles, permissions and tenant claims, `aud`, `exp`. Nothing about the token's shape is new, which is
the point — `JwtAuthenticator` validates it exactly as it validates an IdP's.

## Decision 1 — only where TesseraQL holds the signing key

`JwtAuthenticator` binds its algorithm from configuration: `HS256` verifies with
`tesseraql.security.jwt.secret`, `RS256` with a `publicKey` or a `jwksUri`. There is no private key
anywhere in the tree, and there will not be one.

So the exchange is available to an application configured for **HS256**, and refused for one
configured for RS256 against an identity provider's JWKS. That is not a limitation to work around,
it is the correct division: an application verifying against an external IdP already has somewhere
to get tokens, and the party holding the signing key is the party that issues.

Minting RS256 would mean publishing a JWKS, rotating with overlap, assigning `kid` and serialising
public keys — the one place [authorization-server.md](authorization-server.md) concluded a JOSE
dependency would be genuinely needed. Declining to issue asymmetrically is what keeps that
dependency out.

The refusal is explicit and names the reason, rather than answering 404 or a generic error: an
operator who asks an RS256 application for a token needs to be told their IdP is the issuer.

## Decision 2 — the exchange raises what a session is worth, so it is guarded like a state change

Today a stolen session cookie yields a browser session bounded by CSRF validation, cookie
attributes and an idle timeout. An exchange endpoint lets that same cookie be converted into a
bearer token carrying none of those, living independently of the cookie that produced it. That is a
genuine escalation of what the cookie is worth, and pretending otherwise would be the whole mistake.

So the endpoint is a **state-changing browser POST** and is treated like its siblings: it validates
the CSRF token against the session, exactly as `system.logout`, `system.logout.others` and
`system.logout.device` do. `CsrfValidator` already refuses when there is no session, so a request
without one cannot reach the minting path.

Issuance is recorded in the audit trail. A token that outlives the session that produced it is a
credential nobody would otherwise know exists.

## Decision 3 — revocation is the lifetime, and the asymmetry is documented

The minted token is a self-contained JWT that `JwtAuthenticator` validates statelessly. Nothing
consults a store, which is what makes the bearer path cheap and what makes revocation impossible
without adding one.

Two options, and the cheap one is chosen: **a short default lifetime is the revocation story.** A
token id checked against a revocation table would restore control and would put a database read in
front of every bearer request on every route — reintroducing exactly the state the bearer path does
not have.

That produces an asymmetry worth stating plainly rather than discovering: a password change
invalidates every session of that subject, and it does **not** invalidate tokens already minted from
those sessions. They expire. The default lifetime is short enough that "expire" is a real answer,
and the key is declared so an operator who wants shorter can have it.

An operator who needs immediate revocation of long-lived credentials wants an API key, which is a
different mechanism this framework already has, with a status column and a client registry.

## Decision 4 — what it reaches, stated so nobody expects more

| Client | Reached |
| --- | --- |
| CI, scripts, `curl` | yes |
| Claude Code, the Codex CLI, the ChatGPT desktop app | yes — all connect from the user's machine |
| Claude Desktop over a local stdio server | not by this; that needs the stdio work Decision 2 records |
| claude.ai, ChatGPT hosted connectors | **no** — they fetch from the vendor's cloud and offer no field for a fixed token |

The dividing line is not the transport, it is which side opens the connection. No token format makes
an unroutable host routable, and no fixed credential can be handed to a client that has nowhere to
put one.

**MCP works through this without the transport gate**, which is why it is worth doing on its own.
`AppMcpServer.call` forwards the caller's `Authorization` header into each `direct:mcp.*` route, so
a primitive declaring `auth: bearer` and a `policy:` already authenticates and authorizes the call.
[audit-hardening.md](audit-hardening.md) slice 6 is about discovery and about the floor under
primitives that declare nothing — not about whether an authenticated call succeeds.

That floor is the reason this ships beside slice 12 rather than alone. Read primitives receive no
`security.defaults.routes`, because MCP documents are loaded into their own collections and never
reach `applySecurityDefaults`; without the MCP defaults block, handing out tokens improves
authentication while leaving "anyone who can reach the port" as the default for reads.

## Configuration

```yaml
tesseraql:
  security:
    token:
      enabled: true      # off by default; issuing credentials is opt-in
      ttl: 15m           # short, because expiry is the revocation story
```

Off by default. An endpoint that converts a session into a credential should exist because somebody
decided it should, not because they upgraded.

## Slices

| # | Slice | Notes |
| --- | --- | --- |
| 1 | MCP security defaults ([audit-hardening.md](audit-hardening.md) slice 12) | The floor; shipped first so tokens did not arrive before it |
| 2 | The exchange endpoint, its CSRF guard and the HS256-only refusal | Two declared keys, **one** error code |
| 3 | A path to the endpoint: the CSRF token in the login answer, `tesseraql token --url`, the console page | [stack-architecture.md](stack-architecture.md) Decision 20; **no** new keys and **no** new error code |

**One code, not two.** The design said two; the runtime refusals turned out to need none of their
own. An unauthenticated caller is refused by `CsrfValidator`, which already raises the shared
unauthenticated and CSRF-failure codes, and inventing parallel ones for this endpoint would have
broken the one-meaning-per-code rule to satisfy a sentence. The single new code is the boot refusal,
`TQL-SEC-4146`.

**Slice 3 was owed and not designed here.** This document built an endpoint whose only credential
arrived inside a page, and then described clients that parse no HTML. Nothing was wrong with the
guard — the CSRF requirement is Decision 2 and stands — but the value it checks had no way out of
the browser, so the endpoint was correct and unreachable. Returning the token in the JSON login
answer is what fixed it, and it changes no posture: the same value already reaches any
authenticated browser through the `<meta name="csrf-token">` tag.

## Out of scope

- **An authorization server.** Unchanged from [authorization-server.md](authorization-server.md):
  TesseraQL issues no OAuth access tokens, runs no `/authorize`, and this endpoint is not a token
  endpoint in the OAuth sense — there is no grant, no client, and no consent.
- **Asymmetric issuance.** Decision 1.
- **Refresh tokens.** A refresh token is a long-lived credential with rotation and reuse detection,
  which is the machinery this document exists to avoid. The session is the refresh mechanism: it is
  already there, already revocable, and already the thing the exchange consults.
- **Scopes.** `Policy.Rule` has role, permission and claim dimensions and no scope concept anywhere
  in the codebase. A token that carried scopes would carry something nothing reads.

## Revisit when

- **A deployment needs the hosted assistants.** That is slices 6 and 7 plus an authorization server,
  and this exchange does not substitute for them. It also does not have to be removed for them to
  land: an application can issue its own tokens for local clients and point hosted ones at an
  identity provider.
- **Immediate revocation is required for these tokens specifically**, rather than for API keys. That
  is a token store, and it should be taken deliberately — it puts a read in front of every bearer
  request.
- **An application wants RS256 issuance.** That is JWKS publication and key rotation, and it should
  arrive with the JOSE dependency decision rather than in front of it.
