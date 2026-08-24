# The built-in authorization server

A stack can issue its own OAuth tokens. Turn it on and an MCP client — Claude Code, Codex,
ChatGPT — connects to a member's MCP surface through discovery, registration, a sign-in, and a
consent screen, with no fixed credential and no developer in the loop. The same issuer signs the
`tesseraql token` exchange, so API automation and interactive clients present tokens the members
validate identically.

This page is the operator's view: turning it on, gating an application's MCP surface, what a
connection looks like, lifetimes, and revocation. How it is built — the decisions, the endpoint
internals, the storage — is recorded in the design document; how routes authenticate in general
is [authentication.md](authentication.md).

## Turning it on

The authorization server is a property of the **stack**, declared in `tesseraql-stack.yml`
([hosting.md](hosting.md)) and off by default:

```yaml
# tesseraql-stack.yml
externalOrigin: https://apps.example.com
security:
  oauth:
    enabled: true
```

Two rules are enforced at boot rather than trusted:

- **The origin is required.** The issuer *is* the origin — metadata, the published keys, and
  every member's derived audience hang off it — so a stack without `externalOrigin` refuses
  (`TQL-OAUTH-3002`) instead of guessing a name.
- **The stack file is the only place.** An application declaring `security.oauth.enabled` in its
  own configuration is refused (`TQL-OAUTH-3000`), and a member declaring its own JWT key source
  while the stack issues is refused as a second issuer (`TQL-OAUTH-3001`). A member's
  `security.jwt` block keeps only claim names and extra audiences.

Signing keys are RSA-2048, generated in the framework database the first time the server starts,
and shared by every replica; retired keys stay published while tokens they signed can still be
alive.

## What the stack now serves

Everything lives at the origin scope, beside the sign-in and the portal:

| Path | What it is |
| --- | --- |
| `/.well-known/oauth-authorization-server` | RFC 8414 metadata — the document clients read first |
| `/.well-known/oauth-protected-resource/<member>/_tesseraql/mcp` | RFC 9728 metadata for one member's MCP surface |
| `/_tesseraql/oauth/authorize` | The authorization endpoint (PKCE `S256` only) |
| `/_tesseraql/oauth/consent` | The consent screen, on the stack's own sign-in |
| `/_tesseraql/oauth/token` | Code and refresh grants |
| `/_tesseraql/oauth/register` | RFC 7591 dynamic client registration |
| `/_tesseraql/oauth/jwks` | The published public keys |
| `/_tesseraql/account/connections` | Each signed-in user's connected clients, with revocation |

Registration is open, because the measured MCP clients register themselves with an ephemeral
loopback callback on every connect. Open registration grants nothing: a registered client holds
no access until a person signs in and approves the consent screen, and public clients hold no
secret at all. A client that asks for `client_secret_basic` is issued a secret once, stored
hashed.

## One issuer, every member

With the server enabled, the stack has one issuer and it signs RS256. Every hosted runtime
derives its validation from the stack file — the origin as issuer, the stack's published keys,
the stack's claim names — and nothing is declared per member. A member reads the derived key set
straight from the shared framework database, so validating stack tokens never depends on the
member's egress allow list (`tesseraql.http.outbound.allowedHosts` answers what the stack may
reach *outside* itself). An app-declared `jwksUri` — an external IdP — still rides that allow
list, and a failed key fetch leaves a rate-limited WARN naming the source.

A member accepts three names of itself beside anything it declares. Its own address —
`origin + base path` — is the per-member boundary: a token granted for one member refuses at
the next. The stack origin is the `tesseraql token` exchange's stack-wide mint. And its own MCP
resource — `address + /_tesseraql/mcp` — is the name an MCP grant carries.

## Gating an application's MCP surface

The MCP transport gate is per application, in the application's configuration, and `public` by
default — nothing changes without opting in:

```yaml
# config/tesseraql.yml (the application's own)
tesseraql:
  mcp:
    auth: bearer          # public | bearer
    # resource: urn:...   # optional override of the derived resource identifier
```

Under `bearer`, the gate demands a token whose audience is this member's MCP resource —
`<origin><base path>/_tesseraql/mcp` unless `tesseraql.mcp.resource` declares another name.
An unauthenticated call answers `401` with a `WWW-Authenticate` challenge naming the RFC 9728
metadata, which is where the measured clients begin discovery. The token then rides through to
each tool's own `security:` block, so per-tool `auth:` and `policy:` gate every call exactly as
they gate a route.

## What connecting looks like

From the operator's side, a first-time connection is four observable steps:

1. The client fetches the challenge's metadata, then the authorization-server metadata, and
   registers itself, carrying a complete loopback `redirect_uri`. Clients re-register with a
   fresh port on every retry; the registration rows are cheap and expected.
2. The person's browser lands on the stack sign-in, then on the consent screen: which client,
   which resource, and — where the person holds several application roles — which acting role
   the connection should carry.
3. Approval redirects the code back to the client's loopback; the client exchanges it at
   `/_tesseraql/oauth/token`. Consent is recorded per client and resource, so reconnecting the
   same client skips the screen.
4. The client refreshes silently from then on. Refresh tokens rotate on every use, a replayed
   old token revokes the chain, and every mint re-reads the subject: a disabled account or a
   revoked acting role ends the connection as `invalid_grant` at the next refresh.

A client that omits the RFC 8707 `resource` parameter is refused as `invalid_target` — the
server never guesses which member a grant was meant for. Every measured client sends it.

## Lifetimes

Two knobs, in the stack file's `security.oauth` block, both parsed as durations:

| Key | Default | Meaning |
| --- | --- | --- |
| `tesseraql.security.oauth.accessTokenTtl` | `15m` | How long a minted access token verifies |
| `tesseraql.security.oauth.refreshTokenTtl` | `30d` | How long a connection survives unused |

Short access tokens are the design's grain: revocation and role changes propagate at refresh
cadence, so lengthening the access token lengthens how long a revoked subject keeps acting.

## Revoking access

Each user sees their own connections at `/_tesseraql/account/connections` — client name, member,
granted time — and revokes there; revocation deletes the consent and the refresh chain together,
so the client's next refresh fails and reconnecting walks the consent screen again. Disabling
the account in IAM Admin has the same effect on every connection at once, at refresh cadence.

## When something refuses

| Symptom | What it means |
| --- | --- |
| `TQL-OAUTH-3002` at boot | The stack enables OAuth without `externalOrigin` — declare the origin |
| `TQL-OAUTH-3000` / `TQL-OAUTH-3001` at boot | OAuth or a JWT key source declared in an application's config — move it to the stack file, or delete it |
| Client loops on `401` with a `resource_metadata` challenge | Its token is absent or expired past refresh — reconnect the client |
| `invalid_target` at `/_tesseraql/oauth/authorize` | The client sent no `resource` — the server refuses rather than guessing a member |
| `TQL-SEC-4143` from a member | The token's audience names some other member — mint for this one (`tesseraql token --url --app-name`, or reconnect the client against this member's MCP URL) |
| WARN: `JWKS fetch from … failed` | An app-declared external `jwksUri` is unreachable or not allow-listed — until it succeeds, its bearer validations fail closed |

## Next

- [app-mcp.md](app-mcp.md) — declaring the MCP tools the gate protects.
- [authentication.md](authentication.md) — the methods behind the sign-in, and how routes gate.
- [hosting.md](hosting.md) — the stack file, the shared framework database, and one sign-in.
